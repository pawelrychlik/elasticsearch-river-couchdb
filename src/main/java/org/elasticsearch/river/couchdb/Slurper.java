package org.elasticsearch.river.couchdb;

import static org.elasticsearch.common.base.Joiner.on;
import static org.elasticsearch.river.couchdb.util.Sleeper.sleepLong;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.io.Closeables;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

public class Slurper implements Runnable {

    private final ESLogger logger;

    private final CouchdbConnectionConfig connectionConfig;
    private final CouchdbDatabaseConfig databaseConfig;
    private final RiverConfig riverConfig;

    private final Client client;
    private final BlockingQueue<String> stream;

    private volatile boolean closed;

    public Slurper(CouchdbConnectionConfig connectionConfig, CouchdbDatabaseConfig databaseConfig,
                   RiverConfig riverConfig, Client client, BlockingQueue<String> stream) {
        this.connectionConfig = connectionConfig;
        this.databaseConfig = databaseConfig;
        this.client = client;
        this.stream = stream;
        this.riverConfig = riverConfig;

        logger = Loggers.getLogger(Slurper.class, riverConfig.getRiverSettings().globalSettings(), name());
    }

    private String name() {
        return on(":").join(getClass().getSimpleName(), databaseConfig.getDatabase());
    }

    @Override
    public void run() {
        while (!closed) {
            try {
                slurp();
            } catch (Exception e) {
                logger.warn("Slurper error for database=[{}].", e, databaseConfig.getDatabase());
                sleepLong("to avoid log flooding");
            }
        }
        logger.info("Closing " + name());
    }

    private void slurp() {
        String lastSeq = readLastSequenceFromIndex();

        String file = "/" + databaseConfig.getDatabase() + "/_changes?feed=continuous&include_docs=true&heartbeat=10000";
        if (databaseConfig.shouldUseFilter()) {
            file += databaseConfig.buildFilterUrlParams();
        }

        if (lastSeq != null) {
            try {
                file = file + "&since=" + URLEncoder.encode(lastSeq, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                // should not happen, but in any case...
                file = file + "&since=" + lastSeq;
            }
        }

        if (logger.isDebugEnabled()) {
            logger.debug("using url [{}], path [{}]", connectionConfig.getUrl(), file);
        }

        HttpURLConnection connection = null;
        InputStream is = null;
        try {
            URL url = new URL(connectionConfig.getUrl(), file);
            connection = (HttpURLConnection) url.openConnection();

            if (connectionConfig.requiresAuthentication()) {
                connection.addRequestProperty("Authorization", connectionConfig.getBasicAuthHeader());
            }
            connection.setDoInput(true);
            connection.setUseCaches(false);

            if (!connectionConfig.shouldVerifyHostname()) {
                ((HttpsURLConnection) connection).setHostnameVerifier(
                        new HostnameVerifier() {
                            public boolean verify(String string, SSLSession ssls) {
                                return true;
                            }
                        }
                );
            }

            is = connection.getInputStream();

            final BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (closed) {
                    return;
                }
                if (line.length() == 0) {
                    logger.trace("[couchdb] heartbeat");
                    continue;
                }
                if (logger.isTraceEnabled()) {
                    logger.trace("[couchdb] {}", line);
                }
                // we put here, so we block if there is no space to add
                stream.put(line);
            }
        } catch (Exception e) {
            Closeables.closeQuietly(is);
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Exception e1) {
                    // ignore
                } finally {
                    connection = null;
                }
            }
            if (closed) {
                return;
            }
            logger.warn("failed to read from _changes, throttling....", e);
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e1) {
                if (closed) {
                    return;
                }
            }
        } finally {
            Closeables.closeQuietly(is);
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Exception e1) {
                    // ignore
                } finally {
                    connection = null;
                }
            }
        }
    }

    private String readLastSequenceFromIndex() {
        client.admin().indices().prepareRefresh(riverConfig.getRiverIndexName()).execute().actionGet();

        GetResponse lastSeqResponse = client.prepareGet(riverConfig.getRiverIndexName(),
                riverConfig.getRiverName().name(), "_seq").execute().actionGet();

        if (lastSeqResponse.isExists()) {
            return parseLastSeq(lastSeqResponse);
        }
        return null;
    }

    private String parseLastSeq(GetResponse lastSeqResponse) {
        @SuppressWarnings("unchecked")
        Map<String, Object> db = (Map) lastSeqResponse.getSourceAsMap().get(databaseConfig.getDatabase());
        return db == null ? null : (String) db.get("last_seq");
    }

    public void close() {
        closed = true;
    }
}