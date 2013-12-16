package com.couchbase.lite.replicator.changetracker;

import com.couchbase.lite.Database;
import com.couchbase.lite.Manager;
import com.couchbase.lite.util.Log;
import com.couchbase.lite.util.URIUtils;

import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.AuthState;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HttpContext;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;


/**
 * Reads the continuous-mode _changes feed of a database, and sends the
 * individual change entries to its client's changeTrackerReceivedChange()
 */
public class ChangeTracker implements Runnable {

    private URL databaseURL;
    private ChangeTrackerClient client;
    private TDChangeTrackerMode mode;
    private Object lastSequenceID;

    private Thread thread;
    private boolean running = false;
    private HttpUriRequest request;

    private String filterName;
    private Map<String, Object> filterParams;
    private List<String> docIDs;

    private Throwable error;


    public enum TDChangeTrackerMode {
        OneShot, LongPoll, Continuous
    }

    public ChangeTracker(URL databaseURL, TDChangeTrackerMode mode,
                         Object lastSequenceID, ChangeTrackerClient client) {
        this.databaseURL = databaseURL;
        this.mode = mode;
        this.lastSequenceID = lastSequenceID;
        this.client = client;
    }

    public void setFilterName(String filterName) {
        this.filterName = filterName;
    }

    public void setFilterParams(Map<String, Object> filterParams) {
        this.filterParams = filterParams;
    }

    public void setDocIds(List<String> docIDs) {
        this.docIDs = docIDs;
    }

    public void setClient(ChangeTrackerClient client) {
        this.client = client;
    }

    public String getDatabaseName() {
        String result = null;
        if (databaseURL != null) {
            result = databaseURL.getPath();
            if (result != null) {
                int pathLastSlashPos = result.lastIndexOf('/');
                if (pathLastSlashPos > 0) {
                    result = result.substring(pathLastSlashPos);
                }
            }
        }
        return result;
    }

    public String getChangesFeedPath() {
        String path = "_changes?feed=";
        switch (mode) {
        case OneShot:
            path += "normal";
            break;
        case LongPoll:
            path += "longpoll&limit=50";
            break;
        case Continuous:
            path += "continuous";
            break;
        }
        path += "&heartbeat=300000";

        if(lastSequenceID != null) {
            path += "&since=" + URLEncoder.encode(lastSequenceID.toString());
        }

        // if docIDs have been passed in, the filterType is inferred to be _doc_ids
        if(docIDs != null) {
           filterName = "_doc_ids"; 
           
            Iterator idIter = docIDs.iterator();
            final StringBuilder tmpStr = new StringBuilder();

            // construct a JSON array of document IDs 
            tmpStr.append("["); 

            while(idIter.hasNext()) {
                String docID = (String)idIter.next();

                if(idIter.hasNext()) {
                    tmpStr.append("\"").append(docID).append("\"").append(",");
                }
                else {
                    tmpStr.append("\"").append(docID).append("\"");
                }
            }

            tmpStr.append("]"); 

            // pass in the StringBuilder, ready for URLEncoder
            filterParams = new HashMap<String, Object>() {{
                put("doc_ids", tmpStr);
            }};
        }

        if(filterName != null) {
            path += "&filter=" + URLEncoder.encode(filterName);
            if(filterParams != null) {
                for (String filterParamKey : filterParams.keySet()) {
                    path += "&" + URLEncoder.encode(filterParamKey) + "=" + URLEncoder.encode(filterParams.get(filterParamKey).toString());
                }
            }
        }

        return path;
    }

    public URL getChangesFeedURL() {
        String dbURLString = databaseURL.toExternalForm();
        if(!dbURLString.endsWith("/")) {
            dbURLString += "/";
        }
        dbURLString += getChangesFeedPath();
        URL result = null;
        try {
            result = new URL(dbURLString);
        } catch(MalformedURLException e) {
            Log.e(Database.TAG, "Changes feed ULR is malformed", e);
        }
        return result;
    }

    @Override
    public void run() {

        running = true;
        HttpClient httpClient;

        if (client == null) {
            // This is a race condition that can be reproduced by calling cbpuller.start() and cbpuller.stop()
            // directly afterwards.  What happens is that by the time the Changetracker thread fires up,
            // the cbpuller has already set this.client to null.  See issue #109
            Log.w(Database.TAG, "ChangeTracker run() loop aborting because client == null");
            return;
        }

        httpClient = client.getHttpClient();
        ChangeTrackerBackoff backoff = new ChangeTrackerBackoff();

        while (running) {

            URL url = getChangesFeedURL();
            request = new HttpGet(url.toString());

            // if the URL contains user info AND if this a DefaultHttpClient
            // then preemptively set the auth credentials
            if (url.getUserInfo() != null) {
                Log.v(Database.TAG, "url.getUserInfo(): " + url.getUserInfo());
                if (url.getUserInfo().contains(":") && !url.getUserInfo().trim().equals(":")) {
                    String[] userInfoSplit = url.getUserInfo().split(":");
                    final Credentials creds = new UsernamePasswordCredentials(
                            URIUtils.decode(userInfoSplit[0]), URIUtils.decode(userInfoSplit[1]));
                    if (httpClient instanceof DefaultHttpClient) {
                        DefaultHttpClient dhc = (DefaultHttpClient) httpClient;

                        HttpRequestInterceptor preemptiveAuth = new HttpRequestInterceptor() {

                            @Override
                            public void process(HttpRequest request,
                                                HttpContext context) throws HttpException,
                                    IOException {
                                AuthState authState = (AuthState) context.getAttribute(ClientContext.TARGET_AUTH_STATE);
                                CredentialsProvider credsProvider = (CredentialsProvider) context.getAttribute(
                                        ClientContext.CREDS_PROVIDER);
                                HttpHost targetHost = (HttpHost) context.getAttribute(ExecutionContext.HTTP_TARGET_HOST);

                                if (authState.getAuthScheme() == null) {
                                    AuthScope authScope = new AuthScope(targetHost.getHostName(), targetHost.getPort());
                                    authState.setAuthScheme(new BasicScheme());
                                    authState.setCredentials(creds);
                                }
                            }
                        };

                        dhc.addRequestInterceptor(preemptiveAuth, 0);
                    }
                } else {
                    Log.w(Database.TAG, "ChangeTracker Unable to parse user info, not setting credentials");
                }
            }

            try {
                String maskedRemoteWithoutCredentials = getChangesFeedURL().toString();
                maskedRemoteWithoutCredentials = maskedRemoteWithoutCredentials.replaceAll("://.*:.*@", "://---:---@");
                Log.v(Database.TAG, "Making request to " + maskedRemoteWithoutCredentials);
                HttpResponse response = httpClient.execute(request);
                StatusLine status = response.getStatusLine();
                if (status.getStatusCode() >= 300) {
                    Log.e(Database.TAG, "Change tracker got error " + Integer.toString(status.getStatusCode()));
                    stop();
                }
                HttpEntity entity = response.getEntity();
                InputStream input = null;
                if (entity != null) {

                    input = entity.getContent();
                    if (mode == TDChangeTrackerMode.LongPoll) {
                        Map<String, Object> fullBody = Manager.getObjectMapper().readValue(input, Map.class);
                        boolean responseOK = receivedPollResponse(fullBody);
                        if (mode == TDChangeTrackerMode.LongPoll && responseOK) {
                            Log.v(Database.TAG, "Starting new longpoll");
                            continue;
                        } else {
                            Log.w(Database.TAG, "Change tracker calling stop");
                            stop();
                        }
                    } else {

                        JsonFactory jsonFactory = Manager.getObjectMapper().getJsonFactory();
                        JsonParser jp = jsonFactory.createJsonParser(input);

                        while (jp.nextToken() != JsonToken.START_ARRAY) {
                            // ignore these tokens
                        }

                        while (jp.nextToken() == JsonToken.START_OBJECT) {
                            Map<String, Object> change = (Map) Manager.getObjectMapper().readValue(jp, Map.class);
                            if (!receivedChange(change)) {
                                Log.w(Database.TAG, String.format("Received unparseable change line from server: %s", change));
                            }

                        }

                        stop();
                        break;

                    }

                    backoff.resetBackoff();

                }
            } catch (Exception e) {

                if (!running && e instanceof IOException) {
                    // in this case, just silently absorb the exception because it
                    // frequently happens when we're shutting down and have to
                    // close the socket underneath our read.
                } else {
                    Log.e(Database.TAG, "Exception in change tracker", e);
                }

                backoff.sleepAppropriateAmountOfTime();

            }
        }
        Log.v(Database.TAG, "Change tracker run loop exiting");
    }

    public boolean receivedChange(final Map<String,Object> change) {
        Object seq = change.get("seq");
        if(seq == null) {
            return false;
        }
        //pass the change to the client on the thread that created this change tracker
        if(client != null) {
            client.changeTrackerReceivedChange(change);
        }
        lastSequenceID = seq;
        return true;
    }

    public boolean receivedPollResponse(Map<String,Object> response) {
        List<Map<String,Object>> changes = (List)response.get("results");
        if(changes == null) {
            return false;
        }
        for (Map<String,Object> change : changes) {
            if(!receivedChange(change)) {
                return false;
            }
        }
        return true;
    }

    public void setUpstreamError(String message) {
        Log.w(Database.TAG, String.format("Server error: %s", message));
        this.error = new Throwable(message);
    }

    public boolean start() {
        this.error = null;
        String maskedRemoteWithoutCredentials = databaseURL.toExternalForm();
        maskedRemoteWithoutCredentials = maskedRemoteWithoutCredentials.replaceAll("://.*:.*@", "://---:---@");
        thread = new Thread(this, "ChangeTracker-" + maskedRemoteWithoutCredentials);
        thread.start();
        return true;
    }

    public void stop() {
        Log.d(Database.TAG, "changed tracker asked to stop");
        running = false;
        thread.interrupt();
        if(request != null) {
            request.abort();
        }

        stopped();
    }

    public void stopped() {
        Log.d(Database.TAG, "change tracker in stopped");
        if (client != null) {
            Log.d(Database.TAG, "posting stopped");
            client.changeTrackerStopped(ChangeTracker.this);
        }
        client = null;
        Log.d(Database.TAG, "change tracker client should be null now");
    }

    public boolean isRunning() {
        return running;
    }

}
