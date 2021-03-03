package com.company.app;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.api.core.SettableApiFuture;
import com.google.api.gax.rpc.ResponseObserver;
import com.google.api.gax.rpc.StreamController;
import com.google.cloud.bigtable.admin.v2.BigtableTableAdminClient;
import com.google.cloud.bigtable.admin.v2.BigtableTableAdminSettings;
import com.google.cloud.bigtable.data.v2.BigtableDataClient;
import com.google.cloud.bigtable.data.v2.BigtableDataSettings;
import com.google.cloud.bigtable.data.v2.models.*;

import static com.google.cloud.bigtable.data.v2.models.Filters.FILTERS;

import com.google.common.util.concurrent.MoreExecutors;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvException;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.HdrHistogram.AtomicHistogram;
import org.HdrHistogram.Histogram;
import org.apache.commons.lang3.StringUtils;

import com.opencsv.CSVReader;

/**
 * Simple BigTable testing api. It writes / reads a single row, to a single table with a single column
 * family having a single column.
 */

class BigTableFeaturestoreLoadSimulator {
    private static final String PROJECT_ID = "data-platform-indodana-staging";
    private static final String INSTANCE_ID = "feature-store-stg";
    private BigtableDataClient dataClient;
    private BigtableTableAdminClient adminClient;
    ExecutorService executor = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(128));
    Histogram histogram = new AtomicHistogram(TimeUnit.MINUTES.toNanos(5), 3);
    List<String> featureIds = new ArrayList<>();
    static Long simStartTimestamp,
                simEndTimestamp,
                simRunLimitSecs = (long)60;

    public static void main(String[] args) throws IOException, CsvException, InterruptedException {
        BigTableFeaturestoreLoadSimulator sim = new BigTableFeaturestoreLoadSimulator();
        sim.setup();
        long simRunTimeMillis = simRunLimitSecs*1000;
        long start = System.currentTimeMillis();
        simStartTimestamp = System.nanoTime();
        do {
            sim.simulateFeaturestoreLoad();
        } while(System.currentTimeMillis() - start < simRunTimeMillis);
        simEndTimestamp = System.nanoTime();
        sim.close();
    }

    public void setup() throws IOException, CsvException {
        BigtableDataSettings settings = BigtableDataSettings.newBuilder()
                .setProjectId(PROJECT_ID)
                .setInstanceId(INSTANCE_ID)
                .build();

        dataClient = BigtableDataClient.create(settings);

        BigtableTableAdminSettings adminSettings = BigtableTableAdminSettings.newBuilder()
                .setProjectId(PROJECT_ID)
                .setInstanceId(INSTANCE_ID)
                .build();

        adminClient = BigtableTableAdminClient.create(adminSettings);

        CSVReader reader = new CSVReaderBuilder(new FileReader("src/main/java/com/company/app/features.csv"))
                .withSkipLines(1).build();
        for (String[] f: reader.readAll()) featureIds.add(f[0]);
    }

    public void close() throws InterruptedException {
        executor.shutdownNow();
        dataClient.close();
        adminClient.close();
        File outputFile = new File("src/main/java/com/company/app/results.hdr");
        try (FileOutputStream fout = new FileOutputStream(outputFile)) {
            histogram.outputPercentileDistribution(new PrintStream(fout), 1000.0);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void simulateFeaturestoreLoad() {
        String objectId = "CLI-8YRXMW71";
        String objectVersionId = "6db859f6-bcb5-4ef4-9652-0b7c2096f3c7";
        long start = System.nanoTime();
        ApiFutures.addCallback(
            deleteData(objectId, objectVersionId),
            new ApiFutureCallback<List<Void>>() {
                @Override
                public void onFailure(Throwable throwable) { }
                @Override
                public void onSuccess(List<Void> voids) {
                    System.out.println("delete successful");
                    ApiFutures.addCallback(
                        insertData(objectId, objectVersionId),
                        new ApiFutureCallback<List<Void>>() {
                            @Override
                            public void onFailure(Throwable throwable) { }
                            @Override
                            public void onSuccess(List<Void> voids) {
                                System.out.println("insert successful");
                                histogram.recordValue(System.nanoTime() - start);
                            }
                        },
                        executor
                    );
                }
            },
            executor
        );
    }

    private SettableApiFuture<Void> updateOv(String objectId, String objectVersionId) {
        SettableApiFuture<Void> updateOvApiFuture = SettableApiFuture.create();
        RowMutation ovMutation = RowMutation.create("object_versions", objectVersionId)
                .deleteRow();
        ApiFutures.addCallback(
                dataClient.mutateRowAsync(ovMutation),
                new ApiFutureCallback<Void>() {
                    @Override
                    public void onFailure(Throwable th) {
                        updateOvApiFuture.setException(th);
                    }

                    @Override
                    public void onSuccess(Void unused) {
                        ApiFutures.addCallback(
                                dataClient.mutateRowAsync(getOvRowMutation(objectId, objectVersionId)),
                                new ApiFutureCallback<Void>() {
                                    @Override
                                    public void onFailure(Throwable th) {
                                        updateOvApiFuture.setException(th);
                                    }

                                    @Override
                                    public void onSuccess(Void unused) {
                                        System.out.println("Update ov done");
                                        updateOvApiFuture.set(null);
                                    }
                                },
                                executor
                        );
                    }
                },
                executor
        );
        return updateOvApiFuture;
    }

    private SettableApiFuture<Void> updateOfs(String objectId, String objectVersionId) {
        SettableApiFuture<Void> updateOfsApiFuture = SettableApiFuture.create();

        Query ofsQuery = Query.create("object_features_scores").filter(
                FILTERS.chain()
                        .filter(FILTERS.family().exactMatch("object_features_scores"))
                        .filter(FILTERS.qualifier().exactMatch("object_id"))
                        .filter(FILTERS.value().exactMatch(objectId))
        );
        BulkMutation ofsBulkMutation = BulkMutation.create("object_features_scores");
        dataClient.readRowsAsync(ofsQuery, new ResponseObserver<Row>() {
            @Override
            public void onStart(StreamController streamController) { }

            @Override
            public void onResponse(Row row) {
                ofsBulkMutation.add(RowMutationEntry.create(row.getKey()).deleteRow());
            }

            @Override
            public void onError(Throwable th) {
                updateOfsApiFuture.setException(th);
            }

            @Override
            public void onComplete() {
                ApiFutures.addCallback(
                        dataClient.bulkMutateRowsAsync(ofsBulkMutation),
                        new ApiFutureCallback<Void>() {
                            @Override
                            public void onFailure(Throwable th) {
                                updateOfsApiFuture.setException(th);
                            }

                            @Override
                            public void onSuccess(Void unused) {
                                System.out.println("ofs delete complete");
                                ApiFutures.addCallback(
                                        dataClient.bulkMutateRowsAsync(getOfsBulkMutation(objectId, objectVersionId)),
                                        new ApiFutureCallback<Void>() {
                                            @Override
                                            public void onFailure(Throwable th) {
                                                updateOfsApiFuture.setException(th);
                                            }

                                            @Override
                                            public void onSuccess(Void unused) {
                                                System.out.println("Update ofs done");
                                                updateOfsApiFuture.set(null);
                                            }
                                        },
                                        executor
                                );
                            }
                        },
                        executor
                );
            }
        });
        return updateOfsApiFuture;
    }

    private RowMutation getOvRowMutation(String objectId, String objectVersionId) {
        return RowMutation.create("object_versions", objectVersionId)
                .setCell("object_versions", "id", objectVersionId)
                .setCell("object_versions", "object_id", objectId)
                .setCell("object_versions", "apollo_timestamp_str", "1584072474291")
                .setCell("object_versions", "apollo_timestamp", String.valueOf(new Date(new Date().getTime())))
                .setCell("object_versions", "storage_location", "apollo/v1/archive/json/d99/orderId=CLI-8YRXMW71/d99-1584072467149.json")
                .setCell("object_versions", "object_version_type", "BEFORE_APPROVAL")
                .setCell("object_versions", "updated_at", String.valueOf(new Date(new Date().getTime())))
                .setCell("object_versions", "created_at", String.valueOf(new Date(new Date().getTime())))
                .setCell("object_versions", "tags", "null")
                .setCell("object_versions", "object_version_status", "Pending");
    }

    private BulkMutation getOfsBulkMutation(String objectId, String objectVersionId) {
        BulkMutation ofsBulkMutation = BulkMutation.create("object_features_scores");
        for (String featureId: featureIds) {
            String ofsId = UUID.randomUUID().toString();
            ofsBulkMutation.add(
                    getOfsRowMutationEntry(ofsId, featureId, objectId, objectVersionId)
            );
        }
        return ofsBulkMutation;
//        dataClient.bulkMutateRowsAsync(ofsBulkMutation)
    }

    private RowMutationEntry getOfsRowMutationEntry(String ofsId, String featureId, String objectId, String objectVersionId) {
        return RowMutationEntry.create(ofsId)
                .setCell("object_features_scores", "id", ofsId)
                .setCell("object_features_scores", "object_id", objectId)
                .setCell("object_features_scores", "feature_id", featureId)
                .setCell("object_features_scores", "feature_slug_name", "days_from_oldest_credit_card_payment_date")
                .setCell("object_features_scores", "scoring_date", String.valueOf(new Date(new Date().getTime())))
                .setCell("object_features_scores", "value", "1.3333333333333333")
                .setCell("object_features_scores", "default_score", 0)
                .setCell("object_features_scores", "split_value", "null")
                .setCell("object_features_scores", "description", "null")
                .setCell("object_features_scores", "result_type", "NUMERIC_LIMIT")
                .setCell("object_features_scores", "created_at", String.valueOf(new Date(new Date().getTime())))
                .setCell("object_features_scores", "updated_at", String.valueOf(new Date(new Date().getTime())))
                .setCell("object_features_scores", "object_version_id", objectVersionId);
    }

    private RowMutationEntry getOfsvRowMutationEntry(String ofsvId, String ofsId) {
        return RowMutationEntry.create(ofsvId)
                .setCell("object_feature_score_variables", "id", ofsvId)
                .setCell("object_feature_score_variables", "object_feature_score_id", ofsId)
                .setCell("object_feature_score_variables", "variables", tt)
                .setCell("object_feature_score_variables", "created_at", String.valueOf(new Date(new Date().getTime())))
                .setCell("object_feature_score_variables", "updated_at", String.valueOf(new Date(new Date().getTime())));
    }

    // abandoned: has some sync operations
    private ApiFuture<List<Void>> deleteData(String objectId, String objectVersionId) {
        // query to get ofsIds
        RowMutation ovMutation = RowMutation.create("object_versions", objectVersionId)
                .deleteRow();
        SettableApiFuture<Void> updateOfsApiFuture = SettableApiFuture.create();
        Query ofsQuery = Query.create("object_features_scores").filter(
                FILTERS.chain()
                        .filter(FILTERS.family().exactMatch("object_features_scores"))
                        .filter(FILTERS.qualifier().exactMatch("object_id"))
                        .filter(FILTERS.value().exactMatch(objectId))
        );
        BulkMutation ofsBulkMutation = BulkMutation.create("object_features_scores");
        for (Row row : dataClient.readRows(ofsQuery)) {
            ofsBulkMutation.add(RowMutationEntry.create(row.getKey()).deleteRow());
        }

//        Query ofsvQuery = Query.create("object_feature_score_variables").filter(
//                FILTERS.chain()
//                        .filter(FILTERS.family().exactMatch("object_feature_score_variables"))
//                        .filter(FILTERS.qualifier().exactMatch("object_id"))
//                        .filter(FILTERS.value().exactMatch(objectId))
//        );
//        BulkMutation ofsvBulkMutation = BulkMutation.create("object_feature_score_variables");
//        for (Row row : dataClient.readRows(ofsvQuery)) {
//            ofsvBulkMutation.add(RowMutationEntry.create(row.getKey()).deleteRow());
//        }
        List<ApiFuture<Void>> mutationsList = new ArrayList<>();
        mutationsList.add(dataClient.mutateRowAsync(ovMutation));
        if (ofsBulkMutation.getEntryCount() > 0)
            mutationsList.add(dataClient.bulkMutateRowsAsync(ofsBulkMutation));
        return ApiFutures.allAsList(mutationsList);
    }

    // abandoned: has some sync operations
    private ApiFuture<List<Void>> insertData(String objectId, String objectVersionId) {
        BulkMutation ofsBulkMutation = BulkMutation.create("object_features_scores");
        BulkMutation ofsvBulkMutation = BulkMutation.create("object_feature_score_variables");
        for (String featureId: featureIds) {
            String ofsId = UUID.randomUUID().toString();
            ofsBulkMutation.add(
                    getOfsRowMutationEntry(ofsId, featureId, objectId, objectVersionId)
            );
//            String ofsvId = UUID.randomUUID().toString();
//            ofsvBulkMutation.add(
//                    getOfsvRowMutationEntry(ofsvId, ofsId)
//            );
        }
        return ApiFutures.allAsList(Arrays.asList(
            dataClient.mutateRowAsync(getOvRowMutation(objectId, objectVersionId)),
            dataClient.bulkMutateRowsAsync(ofsBulkMutation)
//            dataClient.bulkMutateRowsAsync(ofsvBulkMutation)
        ));
    }
    String tt = "{\"sharath2\":\""+StringUtils.repeat('a', 300)+"\"}";
}