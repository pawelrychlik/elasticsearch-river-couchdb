package org.elasticsearch.river.couchdb.kernel.index;

import static org.elasticsearch.client.Requests.deleteRequest;
import static org.elasticsearch.client.Requests.indexRequest;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.river.couchdb.CouchdbDatabaseConfig;
import org.elasticsearch.river.couchdb.IndexConfig;
import org.elasticsearch.river.couchdb.RiverConfig;
import org.elasticsearch.river.couchdb.util.LoggerHelper;
import org.elasticsearch.script.ExecutableScript;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class Indexer implements Runnable {

    private ESLogger logger;

    private BlockingQueue<String> stream;
    private Client client;

    private IndexConfig indexConfig;
    private CouchdbDatabaseConfig databaseConfig;
    private RiverConfig riverConfig;
    private ExecutableScript script;

    private volatile boolean closed;

    public Indexer() {
        logger = LoggerHelper.indexerLogger(Indexer.class, databaseConfig.getDatabase());
    }

    @Override
    public void run() {
        while (true) {
            if (closed) {
                return;
            }
            String s;
            try {
                s = stream.take();
            } catch (InterruptedException e) {
                if (closed) {
                    return;
                }
                continue;
            }
            BulkRequestBuilder bulk = client.prepareBulk();
            Object lastSeq = null;
            Object lineSeq = processLine(s, bulk);
            if (lineSeq != null) {
                lastSeq = lineSeq;
            }

            // spin a bit to see if we can get some more changes
            try {
                while ((s = stream.poll(indexConfig.getBulkTimeout().millis(), TimeUnit.MILLISECONDS)) != null) {
                    lineSeq = processLine(s, bulk);
                    if (lineSeq != null) {
                        lastSeq = lineSeq;
                    }

                    if (bulk.numberOfActions() >= indexConfig.getBulkSize()) {
                        break;
                    }
                }
            } catch (InterruptedException e) {
                if (closed) {
                    return;
                }
            }

            if (lastSeq != null) {
                try {
                    // we always store it as a string
                    String lastSeqAsString = null;
                    if (lastSeq instanceof List) {
                        // bigcouch uses array for the seq
                        try {
                            XContentBuilder builder = XContentFactory.jsonBuilder();
                            //builder.startObject();
                            builder.startArray();
                            for (Object value : ((List) lastSeq)) {
                                builder.value(value);
                            }
                            builder.endArray();
                            //builder.endObject();
                            lastSeqAsString = builder.string();
                        } catch (Exception e) {
                            logger.error("failed to convert last_seq to a json string", e);
                        }
                    } else {
                        lastSeqAsString = lastSeq.toString();
                    }
                    if (logger.isTraceEnabled()) {
                        logger.trace("processing [_seq  ]: [{}]/[{}]/[{}], last_seq [{}]",
                                riverConfig.getRiverIndexName(), riverConfig.getRiverName().name(), "_seq", lastSeqAsString);
                    }
                    bulk.add(indexRequest(riverConfig.getRiverIndexName()).type(riverConfig.getRiverName().name()).id("_seq")
                            .source(jsonBuilder().startObject().startObject("couchdb").field("last_seq", lastSeqAsString).endObject().endObject()));
                } catch (IOException e) {
                    logger.warn("failed to add last_seq entry to bulk indexing");
                }
            }

            try {
                BulkResponse response = bulk.execute().actionGet();
                if (response.hasFailures()) {
                    // TODO write to exception queue?
                    logger.warn("failed to execute" + response.buildFailureMessage());
                }
            } catch (Exception e) {
                logger.warn("failed to execute bulk", e);
            }
        }
    }

    @SuppressWarnings({"unchecked"})
    private Object processLine(String s, BulkRequestBuilder bulk) {
        Map<String, Object> ctx;
        try {
            ctx = XContentFactory.xContent(XContentType.JSON).createParser(s).mapAndClose();
        } catch (IOException e) {
            logger.warn("failed to parse {}", e, s);
            return null;
        }
        if (ctx.containsKey("error")) {
            logger.warn("received error {}", s);
            return null;
        }
        Object seq = ctx.get("seq");
        String id = ctx.get("id").toString();

        // Ignore design documents
        if (id.startsWith("_design/")) {
            if (logger.isTraceEnabled()) {
                logger.trace("ignoring design document {}", id);
            }
            return seq;
        }

        if (script != null) {
            script.setNextVar("ctx", ctx);
            try {
                script.run();
                // we need to unwrap the ctx...
                ctx = (Map<String, Object>) script.unwrap(ctx);
            } catch (Exception e) {
                logger.warn("failed to script process {}, ignoring", e, ctx);
                return seq;
            }
        }

        if (ctx.containsKey("ignore") && ctx.get("ignore").equals(Boolean.TRUE)) {
            // ignore dock
        } else if (ctx.containsKey("deleted") && ctx.get("deleted").equals(Boolean.TRUE)) {
            String index = extractIndex(ctx);
            String type = extractType(ctx);
            if (logger.isTraceEnabled()) {
                logger.trace("processing [delete]: [{}]/[{}]/[{}]", index, type, id);
            }
            bulk.add(deleteRequest(index).type(type).id(id).routing(extractRouting(ctx)).parent(extractParent(ctx)));
        } else if (ctx.containsKey("doc")) {
            String index = extractIndex(ctx);
            String type = extractType(ctx);
            Map<String, Object> doc = (Map<String, Object>) ctx.get("doc");

            // Remove _attachment from doc if needed
            if (databaseConfig.shouldIgnoreAttachments()) {
                // no need to log that we removed it, the doc indexed will be shown without it
                doc.remove("_attachments");
            } else {
                // TODO by now, couchDB river does not really store attachments but only attachments meta infomration
                // So we perhaps need to fully support attachments
            }

            if (logger.isTraceEnabled()) {
                logger.trace("processing [index ]: [{}]/[{}]/[{}], source {}", index, type, id, doc);
            }

            bulk.add(indexRequest(index).type(type).id(id).source(doc).routing(extractRouting(ctx)).parent(extractParent(ctx)));
        } else {
            logger.warn("ignoring unknown change {}", s);
        }
        return seq;
    }

    private String extractParent(Map<String, Object> ctx) {
        return (String) ctx.get("_parent");
    }

    private String extractRouting(Map<String, Object> ctx) {
        return (String) ctx.get("_routing");
    }

    private String extractType(Map<String, Object> ctx) {
        String type = (String) ctx.get("_type");
        if (type == null) {
            type = indexConfig.getType();
        }
        return type;
    }

    private String extractIndex(Map<String, Object> ctx) {
        String index = (String) ctx.get("_index");
        if (index == null) {
            index = indexConfig.getName();
        }
        return index;
    }
}
