/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.aggregate.adtech.worker.aggregation.concurrent;

import static com.google.aggregate.adtech.worker.AggregationWorkerReturnCode.INPUT_DATA_READ_FAILED;
import static com.google.aggregate.adtech.worker.AggregationWorkerReturnCode.INTERNAL_ERROR;
import static com.google.aggregate.adtech.worker.AggregationWorkerReturnCode.INVALID_JOB;
import static com.google.aggregate.adtech.worker.AggregationWorkerReturnCode.PERMISSION_ERROR;
import static com.google.aggregate.adtech.worker.AggregationWorkerReturnCode.PRIVACY_BUDGET_AUTHENTICATION_ERROR;
import static com.google.aggregate.adtech.worker.AggregationWorkerReturnCode.PRIVACY_BUDGET_AUTHORIZATION_ERROR;
import static com.google.aggregate.adtech.worker.AggregationWorkerReturnCode.PRIVACY_BUDGET_EXHAUSTED;
import static com.google.aggregate.adtech.worker.AggregationWorkerReturnCode.RESULT_WRITE_ERROR;
import static com.google.aggregate.adtech.worker.AggregationWorkerReturnCode.UNSUPPORTED_REPORT_VERSION;
import static com.google.aggregate.adtech.worker.aggregation.concurrent.ConcurrentAggregationProcessor.JOB_PARAM_ATTRIBUTION_REPORT_TO;
import static com.google.aggregate.adtech.worker.aggregation.concurrent.ConcurrentAggregationProcessor.JOB_PARAM_DEBUG_PRIVACY_EPSILON;
import static com.google.aggregate.adtech.worker.aggregation.concurrent.ConcurrentAggregationProcessor.JOB_PARAM_DEBUG_RUN;
import static com.google.aggregate.adtech.worker.model.ErrorCounter.NUM_REPORTS_WITH_ERRORS;
import static com.google.aggregate.adtech.worker.model.SharedInfo.LATEST_VERSION;
import static com.google.aggregate.adtech.worker.util.JobResultHelper.RESULT_REPORTS_WITH_ERRORS_EXCEEDED_THRESHOLD_MESSAGE;
import static com.google.aggregate.adtech.worker.util.JobResultHelper.RESULT_SUCCESS_MESSAGE;
import static com.google.aggregate.adtech.worker.util.JobResultHelper.RESULT_SUCCESS_WITH_ERRORS_MESSAGE;
import static com.google.aggregate.adtech.worker.util.JobUtils.JOB_PARAM_OUTPUT_DOMAIN_BLOB_PREFIX;
import static com.google.aggregate.adtech.worker.util.JobUtils.JOB_PARAM_OUTPUT_DOMAIN_BUCKET_NAME;
import static com.google.aggregate.adtech.worker.util.JobUtils.JOB_PARAM_REPORT_ERROR_THRESHOLD_PERCENTAGE;
import static com.google.aggregate.adtech.worker.util.NumericConversions.createBucketFromInt;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.acai.Acai;
import com.google.acai.TestScoped;
import com.google.aggregate.adtech.worker.AggregationWorkerReturnCode;
import com.google.aggregate.adtech.worker.Annotations.BlockingThreadPool;
import com.google.aggregate.adtech.worker.Annotations.DomainOptional;
import com.google.aggregate.adtech.worker.Annotations.EnableStackTraceInResponse;
import com.google.aggregate.adtech.worker.Annotations.EnableThresholding;
import com.google.aggregate.adtech.worker.Annotations.MaxDepthOfStackTrace;
import com.google.aggregate.adtech.worker.Annotations.NonBlockingThreadPool;
import com.google.aggregate.adtech.worker.Annotations.ReportErrorThresholdPercentage;
import com.google.aggregate.adtech.worker.ResultLogger;
import com.google.aggregate.adtech.worker.aggregation.domain.AvroOutputDomainProcessor;
import com.google.aggregate.adtech.worker.aggregation.domain.OutputDomainProcessor;
import com.google.aggregate.adtech.worker.aggregation.domain.TextOutputDomainProcessor;
import com.google.aggregate.adtech.worker.aggregation.engine.AggregationEngine;
import com.google.aggregate.adtech.worker.configs.PrivacyParametersSupplier;
import com.google.aggregate.adtech.worker.configs.PrivacyParametersSupplier.NoisingDelta;
import com.google.aggregate.adtech.worker.configs.PrivacyParametersSupplier.NoisingDistribution;
import com.google.aggregate.adtech.worker.configs.PrivacyParametersSupplier.NoisingEpsilon;
import com.google.aggregate.adtech.worker.configs.PrivacyParametersSupplier.NoisingL1Sensitivity;
import com.google.aggregate.adtech.worker.decryption.DeserializingReportDecrypter;
import com.google.aggregate.adtech.worker.decryption.RecordDecrypter;
import com.google.aggregate.adtech.worker.decryption.hybrid.HybridDecryptionModule;
import com.google.aggregate.adtech.worker.exceptions.AggregationJobProcessException;
import com.google.aggregate.adtech.worker.exceptions.ResultLogException;
import com.google.aggregate.adtech.worker.model.AggregatedFact;
import com.google.aggregate.adtech.worker.model.AvroRecordEncryptedReportConverter;
import com.google.aggregate.adtech.worker.model.DebugBucketAnnotation;
import com.google.aggregate.adtech.worker.model.EncryptedReport;
import com.google.aggregate.adtech.worker.model.ErrorCounter;
import com.google.aggregate.adtech.worker.model.Report;
import com.google.aggregate.adtech.worker.model.serdes.PayloadSerdes;
import com.google.aggregate.adtech.worker.model.serdes.SharedInfoSerdes;
import com.google.aggregate.adtech.worker.model.serdes.cbor.CborPayloadSerdes;
import com.google.aggregate.adtech.worker.testing.FakeDecryptionKeyService;
import com.google.aggregate.adtech.worker.testing.FakeReportGenerator;
import com.google.aggregate.adtech.worker.testing.FakeValidator;
import com.google.aggregate.adtech.worker.testing.InMemoryResultLogger;
import com.google.aggregate.adtech.worker.util.NumericConversions;
import com.google.aggregate.adtech.worker.validation.ReportValidator;
import com.google.aggregate.adtech.worker.validation.ReportVersionValidator;
import com.google.aggregate.perf.StopwatchExporter;
import com.google.aggregate.perf.StopwatchRegistry;
import com.google.aggregate.perf.export.NoOpStopwatchExporter;
import com.google.aggregate.privacy.budgeting.bridge.FakePrivacyBudgetingServiceBridge;
import com.google.aggregate.privacy.budgeting.bridge.PrivacyBudgetingServiceBridge;
import com.google.aggregate.privacy.budgeting.bridge.PrivacyBudgetingServiceBridge.PrivacyBudgetUnit;
import com.google.aggregate.privacy.budgeting.bridge.PrivacyBudgetingServiceBridge.PrivacyBudgetingServiceBridgeException;
import com.google.aggregate.privacy.budgeting.bridge.UnlimitedPrivacyBudgetingServiceBridge;
import com.google.aggregate.privacy.noise.Annotations.Threshold;
import com.google.aggregate.privacy.noise.NoiseApplier;
import com.google.aggregate.privacy.noise.NoisedAggregationRunner;
import com.google.aggregate.privacy.noise.NoisedAggregationRunnerImpl;
import com.google.aggregate.privacy.noise.proto.Params.NoiseParameters.Distribution;
import com.google.aggregate.privacy.noise.proto.Params.PrivacyParameters;
import com.google.aggregate.privacy.noise.testing.ConstantNoiseModule.ConstantNoiseApplier;
import com.google.aggregate.privacy.noise.testing.FakeNoiseApplierSupplier;
import com.google.aggregate.protocol.avro.AvroOutputDomainReaderFactory;
import com.google.aggregate.protocol.avro.AvroOutputDomainRecord;
import com.google.aggregate.protocol.avro.AvroOutputDomainWriter;
import com.google.aggregate.protocol.avro.AvroOutputDomainWriterFactory;
import com.google.aggregate.protocol.avro.AvroReportWriter;
import com.google.aggregate.protocol.avro.AvroReportWriterFactory;
import com.google.aggregate.shared.mapper.TimeObjectMapper;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.io.ByteSource;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.multibindings.Multibinder;
import com.google.privacysandbox.otel.OtlpJsonLoggingOTelConfigurationModule;
import com.google.scp.operator.cpio.blobstorageclient.BlobStorageClient;
import com.google.scp.operator.cpio.blobstorageclient.model.DataLocation;
import com.google.scp.operator.cpio.blobstorageclient.model.DataLocation.BlobStoreDataLocation;
import com.google.scp.operator.cpio.blobstorageclient.testing.FSBlobStorageClientModule;
import com.google.scp.operator.cpio.cryptoclient.DecryptionKeyService;
import com.google.scp.operator.cpio.cryptoclient.model.ErrorReason;
import com.google.scp.operator.cpio.distributedprivacybudgetclient.StatusCode;
import com.google.scp.operator.cpio.jobclient.model.Job;
import com.google.scp.operator.cpio.jobclient.model.JobResult;
import com.google.scp.operator.cpio.jobclient.testing.FakeJobGenerator;
import com.google.scp.operator.protos.shared.backend.ErrorCountProto.ErrorCount;
import com.google.scp.operator.protos.shared.backend.ErrorSummaryProto.ErrorSummary;
import com.google.scp.operator.protos.shared.backend.RequestInfoProto.RequestInfo;
import com.google.scp.operator.protos.shared.backend.ResultInfoProto.ResultInfo;
import com.google.scp.shared.proto.ProtoUtil;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ConcurrentAggregationProcessorTest {

  private static final Instant FIXED_TIME = Instant.parse("2021-01-01T00:00:00Z");

  @Rule public final Acai acai = new Acai(TestEnv.class);
  @Rule public final TemporaryFolder testWorkingDir = new TemporaryFolder();
  @Inject AvroReportWriterFactory reportWriterFactory;
  @Inject AvroRecordEncryptedReportConverter avroConverter;
  // These are the same reader and decrypter that are inside the processor, through Guice bindings.
  @Inject FakeNoiseApplierSupplier fakeNoiseApplierSupplier;
  @Inject FakeValidator fakeValidator;
  @Inject InMemoryResultLogger resultLogger;
  @Inject FakeDecryptionKeyService fakeDecryptionKeyService;
  @Inject PayloadSerdes payloadSerdes;
  @Inject ProxyPrivacyBudgetingServiceBridge privacyBudgetingServiceBridge;
  @Inject SharedInfoSerdes sharedInfoSerdes;
  @Inject AvroOutputDomainWriterFactory domainWriterFactory;
  @Inject OutputDomainProcessorHelper outputDomainProcessorHelper;
  private Path outputDomainDirectory;
  private Path reportsDirectory;
  private Path invalidReportsDirectory;
  private Job ctx;
  private Job ctxInvalidReport;
  private JobResult expectedJobResult;
  private ResultInfo.Builder resultInfoBuilder;
  private ImmutableList<EncryptedReport> encryptedReports1;
  private ImmutableList<EncryptedReport> encryptedReports2;
  private String reportId1 = String.valueOf(UUID.randomUUID());
  private String reportId2 = String.valueOf(UUID.randomUUID());
  private String reportId3 = String.valueOf(UUID.randomUUID());
  private String reportId4 = String.valueOf(UUID.randomUUID());
  private String reportId5 = String.valueOf(UUID.randomUUID());

  // Under test
  @Inject private Provider<ConcurrentAggregationProcessor> processor;

  @Before
  public void beforeTest() {
    outputDomainProcessorHelper.setAvroOutputDomainProcessor(true);
    fakeNoiseApplierSupplier.setFakeNoiseApplier(new ConstantNoiseApplier(0));
    fakeValidator.setReportIdShouldReturnError(ImmutableSet.of());
  }

  private EncryptedReport generateEncryptedReportWithVersion(
      int param, String reportId, String version) {
    String keyId = UUID.randomUUID().toString();
    Report report =
        FakeReportGenerator.generateWithFixedReportId(param, reportId, /* reportVersion */ version);
    String sharedInfoString = sharedInfoSerdes.reverse().convert(Optional.of(report.sharedInfo()));
    try {
      ByteSource firstReportBytes =
          fakeDecryptionKeyService.generateCiphertext(
              keyId,
              payloadSerdes.reverse().convert(Optional.of(report.payload())),
              sharedInfoString);
      return EncryptedReport.builder()
          .setPayload(firstReportBytes)
          .setKeyId(keyId)
          .setSharedInfo(sharedInfoString)
          .build();
    } catch (Exception ex) {
      // return null to fail test
      return null;
    }
  }

  private EncryptedReport generateEncryptedReport(int param, String reportId) {
    return generateEncryptedReportWithVersion(param, reportId, LATEST_VERSION);
  }

  private EncryptedReport generateInvalidVersionEncryptedReport(
      int param, String reportId, String invalidVersion) {
    return generateEncryptedReportWithVersion(param, reportId, invalidVersion);
  }

  @Before
  public void setUp() throws Exception {
    privacyBudgetingServiceBridge.setPrivacyBudgetingServiceBridgeImpl(
        new UnlimitedPrivacyBudgetingServiceBridge());

    outputDomainDirectory = testWorkingDir.getRoot().toPath().resolve("output_domain");
    reportsDirectory = testWorkingDir.getRoot().toPath().resolve("reports");
    invalidReportsDirectory = testWorkingDir.getRoot().toPath().resolve("invalid-reports");

    Files.createDirectory(outputDomainDirectory);
    Files.createDirectory(reportsDirectory);
    Files.createDirectory(invalidReportsDirectory);

    ctx = FakeJobGenerator.generateBuilder("foo").build();
    ctx =
        ctx.toBuilder()
            .setRequestInfo(
                getRequestInfoWithInputDataBucketName(ctx.requestInfo(), reportsDirectory))
            .build();
    resultInfoBuilder =
        ResultInfo.newBuilder()
            .setReturnCode(AggregationWorkerReturnCode.SUCCESS.name())
            .setReturnMessage(RESULT_SUCCESS_MESSAGE)
            .setFinishedAt(ProtoUtil.toProtoTimestamp(FIXED_TIME))
            .setErrorSummary(ErrorSummary.getDefaultInstance());
    expectedJobResult = makeExpectedJobResult();

    // Job context for job with invalid version input report.
    ctxInvalidReport = FakeJobGenerator.generateBuilder("bar").build();
    ctxInvalidReport =
        ctxInvalidReport.toBuilder()
            .setRequestInfo(
                getRequestInfoWithInputDataBucketName(
                    ctxInvalidReport.requestInfo(), invalidReportsDirectory))
            .build();

    EncryptedReport firstReport = generateEncryptedReport(1, reportId1);
    EncryptedReport secondReport = generateEncryptedReport(2, reportId2);

    // thirdReport is same as firstReport but has new report id
    EncryptedReport thirdReport = generateEncryptedReport(1, reportId3);
    // fourthReport is same as secondReport but has new report id
    EncryptedReport fourthReport = generateEncryptedReport(2, reportId4);

    encryptedReports1 = ImmutableList.of(firstReport, secondReport);
    encryptedReports2 = ImmutableList.of(thirdReport, fourthReport);
    // 2 shards of same contents.
    writeReports(reportsDirectory.resolve("reports_1.avro"), encryptedReports1);
    writeReports(reportsDirectory.resolve("reports_2.avro"), encryptedReports2);

    EncryptedReport invalidEncryptedReport =
        generateInvalidVersionEncryptedReport(1, reportId5, "1.0");
    writeReports(
        invalidReportsDirectory.resolve("invalid_reports.avro"),
        ImmutableList.of(invalidEncryptedReport));
  }

  private RequestInfo getRequestInfoWithInputDataBucketName(
      RequestInfo requestInfo, Path inputReportDirectory) {
    Map<String, String> jobParameters = new HashMap<>(requestInfo.getJobParameters());
    jobParameters.put("report_error_threshold_percentage", "100");
    return requestInfo.toBuilder()
        .putAllJobParameters(jobParameters)
        // Simulating shards of input.
        .setInputDataBucketName(inputReportDirectory.toAbsolutePath().toString())
        .setInputDataBlobPrefix("")
        .build();
  }

  @Test
  public void aggregate() throws Exception {
    JobResult jobResultProcessor = processor.get().process(ctx);

    assertThat(jobResultProcessor).isEqualTo(expectedJobResult);
    assertThat(resultLogger.getMaterializedAggregationResults().getMaterializedAggregations())
        .containsExactly(
            AggregatedFact.create(/* bucket= */ createBucketFromInt(1), /* metric= */ 2, 2L),
            AggregatedFact.create(/* bucket= */ createBucketFromInt(2), /* metric= */ 8, 8L));
  }

  @Test
  public void aggregate_invalidVersionReport() throws Exception {
    AggregationJobProcessException ex =
        assertThrows(
            AggregationJobProcessException.class, () -> processor.get().process(ctxInvalidReport));
    assertThat(ex.getCode()).isEqualTo(UNSUPPORTED_REPORT_VERSION);
    assertThat(ex.getMessage())
        .contains(
            "Current Aggregation Service deployment does not support Aggregatable reports with"
                + " shared_info.version");
  }

  @Test
  public void aggregate_noOutputDomain_thresholding() throws Exception {
    fakeNoiseApplierSupplier.setFakeNoiseApplier(new ConstantNoiseApplier(-3));

    JobResult jobResultProcessor = processor.get().process(ctx);

    assertThat(jobResultProcessor).isEqualTo(expectedJobResult);
    assertThat(resultLogger.getMaterializedAggregationResults().getMaterializedAggregations())
        .containsExactly(
            AggregatedFact.create(/* bucket= */ createBucketFromInt(2), /* metric= */ 5, 8L));
  }

  @Test
  public void aggregate_withOutputDomain_thresholding() throws Exception {
    writeOutputDomainAvroFile(
        outputDomainDirectory.resolve("output_domain_1.avro"),
        "2"); // 1 is not in output domain, so thresholding applies
    DataLocation outputDomainLocation = getOutputDomainLocation();
    ImmutableMap<String, String> jobParams =
        ImmutableMap.of(
            JOB_PARAM_OUTPUT_DOMAIN_BUCKET_NAME,
            outputDomainLocation.blobStoreDataLocation().bucket(),
            JOB_PARAM_OUTPUT_DOMAIN_BLOB_PREFIX,
            outputDomainLocation.blobStoreDataLocation().key());
    ctx =
        ctx.toBuilder()
            .setRequestInfo(
                ctx.requestInfo().toBuilder()
                    .putAllJobParameters(
                        combineJobParams(ctx.requestInfo().getJobParameters(), jobParams))
                    .build())
            .build();
    fakeNoiseApplierSupplier.setFakeNoiseApplier(new ConstantNoiseApplier(-3));

    JobResult jobResultProcessor = processor.get().process(ctx);

    assertThat(jobResultProcessor).isEqualTo(makeExpectedJobResult());
    assertThat(resultLogger.getMaterializedAggregationResults().getMaterializedAggregations())
        .containsExactly(
            AggregatedFact.create(/* bucket= */ createBucketFromInt(2), /* metric= */ 5, 8L));
  }

  @Test
  public void aggregate_withOutputDomain_thresholding_debugEpsilon() throws Exception {
    writeOutputDomainAvroFile(
        outputDomainDirectory.resolve("output_domain_1.avro"),
        "2"); // 1 is not in output domain, so thresholding applies
    DataLocation outputDomainLocation = getOutputDomainLocation();
    ImmutableMap<String, String> jobParams =
        ImmutableMap.of(
            JOB_PARAM_DEBUG_PRIVACY_EPSILON,
            "0.5",
            JOB_PARAM_OUTPUT_DOMAIN_BUCKET_NAME,
            outputDomainLocation.blobStoreDataLocation().bucket(),
            JOB_PARAM_OUTPUT_DOMAIN_BLOB_PREFIX,
            outputDomainLocation.blobStoreDataLocation().key());

    // Set output domain location and debug_privacy_epsilon in job.
    ctx =
        ctx.toBuilder()
            .setRequestInfo(
                ctx.requestInfo().toBuilder()
                    .putAllJobParameters(
                        combineJobParams(ctx.requestInfo().getJobParameters(), jobParams))
                    .build())
            .build();
    fakeNoiseApplierSupplier.setFakeNoiseApplier(new ConstantNoiseApplier(-3));

    JobResult jobResultProcessor = processor.get().process(ctx);

    assertThat(jobResultProcessor).isEqualTo(makeExpectedJobResult());
    assertThat(resultLogger.getMaterializedAggregationResults().getMaterializedAggregations())
        .containsExactly(
            AggregatedFact.create(/* bucket= */ createBucketFromInt(2), /* metric= */ 5, 8L));
  }

  @Test
  public void aggregate_withOutputDomain_thresholding_debugEpsilonMalformedValue()
      throws Exception {
    writeOutputDomainAvroFile(
        outputDomainDirectory.resolve("output_domain_1.avro"),
        "2"); // 1 is not in output domain, so thresholding applies
    DataLocation outputDomainLocation = getOutputDomainLocation();
    ImmutableMap<String, String> jobParams =
        ImmutableMap.of(
            JOB_PARAM_DEBUG_PRIVACY_EPSILON,
            "",
            JOB_PARAM_OUTPUT_DOMAIN_BUCKET_NAME,
            outputDomainLocation.blobStoreDataLocation().bucket(),
            JOB_PARAM_OUTPUT_DOMAIN_BLOB_PREFIX,
            outputDomainLocation.blobStoreDataLocation().key());

    // Set output domain location and debug_privacy_epsilon in job.
    ctx =
        ctx.toBuilder()
            .setRequestInfo(
                ctx.requestInfo().toBuilder()
                    .putAllJobParameters(
                        combineJobParams(ctx.requestInfo().getJobParameters(), jobParams))
                    .build())
            .build();
    fakeNoiseApplierSupplier.setFakeNoiseApplier(new ConstantNoiseApplier(-3));

    JobResult jobResultProcessor = processor.get().process(ctx);

    assertThat(jobResultProcessor).isEqualTo(makeExpectedJobResult());
    assertThat(resultLogger.getMaterializedAggregationResults().getMaterializedAggregations())
        .containsExactly(
            AggregatedFact.create(/* bucket= */ createBucketFromInt(2), /* metric= */ 5, 8L));
  }

  @Test
  public void aggregate_withOutputDomain_thresholding_debugEpsilonOutOfRange() throws Exception {
    writeOutputDomainAvroFile(
        outputDomainDirectory.resolve("output_domain_1.avro"),
        "2"); // 1 is not in output domain, so thresholding applies
    DataLocation outputDomainLocation = getOutputDomainLocation();
    ImmutableMap<String, String> jobParams =
        ImmutableMap.of(
            JOB_PARAM_DEBUG_PRIVACY_EPSILON,
            "0",
            JOB_PARAM_OUTPUT_DOMAIN_BUCKET_NAME,
            outputDomainLocation.blobStoreDataLocation().bucket(),
            JOB_PARAM_OUTPUT_DOMAIN_BLOB_PREFIX,
            outputDomainLocation.blobStoreDataLocation().key());
    // Set output domain location and debug_privacy_epsilon in job.
    ctx =
        ctx.toBuilder()
            .setRequestInfo(
                ctx.requestInfo().toBuilder()
                    .putAllJobParameters(
                        combineJobParams(ctx.requestInfo().getJobParameters(), jobParams))
                    .build())
            .build();
    fakeNoiseApplierSupplier.setFakeNoiseApplier(new ConstantNoiseApplier(-3));

    AggregationJobProcessException ex =
        assertThrows(AggregationJobProcessException.class, () -> processor.get().process(ctx));
    assertThat(ex.getCode()).isEqualTo(INVALID_JOB);
  }

  @Test
  public void aggregate_noOutputDomain_thresholding_withoutDebugRun() throws Exception {
    fakeNoiseApplierSupplier.setFakeNoiseApplier(new ConstantNoiseApplier(-3));

    JobResult jobResultProcessor = processor.get().process(ctx);
    ResultLogException exception =
        assertThrows(
            ResultLogException.class, () -> resultLogger.getMaterializedDebugAggregationResults());

    assertThat(jobResultProcessor).isEqualTo(expectedJobResult);
    assertThat(resultLogger.getMaterializedAggregationResults().getMaterializedAggregations())
        .containsExactly(
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(2), /* metric= */ 5, /* unnoisedMetric= */ 8L));
    assertThat(exception).hasCauseThat().isInstanceOf(IllegalStateException.class);
    assertThat(exception)
        .hasMessageThat()
        .contains("MaterializedAggregations is null. Maybe results did not get logged.");
  }

  @Test
  public void aggregate_noOutputDomain_thresholding_withDebugRun() throws Exception {
    ImmutableMap<String, String> jobParams = ImmutableMap.of(JOB_PARAM_DEBUG_RUN, "true");
    // Set debug run params in job.
    ctx =
        ctx.toBuilder()
            .setRequestInfo(
                ctx.requestInfo().toBuilder()
                    .putAllJobParameters(
                        combineJobParams(ctx.requestInfo().getJobParameters(), jobParams))
                    .build())
            .build();
    fakeNoiseApplierSupplier.setFakeNoiseApplier(new ConstantNoiseApplier(-3));

    JobResult jobResultProcessor = processor.get().process(ctx);

    assertThat(jobResultProcessor).isEqualTo(expectedJobResult);
    // Confirm correct success code is returned, and not an alternate debug mode success code
    assertThat(jobResultProcessor.resultInfo().getReturnCode())
        .isEqualTo(AggregationWorkerReturnCode.SUCCESS.name());
    assertThat(resultLogger.getMaterializedAggregationResults().getMaterializedAggregations())
        .containsExactly(
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(2), /* metric= */ 5, /* unnoisedMetric= */ 8L));
    assertThat(resultLogger.getMaterializedDebugAggregationResults().getMaterializedAggregations())
        .containsExactly(
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(1),
                /* metric= */ -1,
                /* unnoisedMetric= */ 2L,
                /* debugAnnotations= */ List.of(DebugBucketAnnotation.IN_REPORTS)),
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(2),
                /* metric= */ 5,
                /* unnoisedMetric= */ 8L,
                /* debugAnnotations= */ List.of(DebugBucketAnnotation.IN_REPORTS)));
  }

  @Test
  public void aggregate_withOutputDomain_thresholding_withDebugRun() throws Exception {
    writeOutputDomainAvroFile(
        outputDomainDirectory.resolve("output_domain_1.avro"),
        "2",
        "3"); // 1 is not in output domain, so thresholding applies
    DataLocation outputDomainLocation = getOutputDomainLocation();
    ImmutableMap<String, String> jobParams =
        ImmutableMap.of(
            JOB_PARAM_DEBUG_RUN,
            "true",
            JOB_PARAM_OUTPUT_DOMAIN_BUCKET_NAME,
            outputDomainLocation.blobStoreDataLocation().bucket(),
            JOB_PARAM_OUTPUT_DOMAIN_BLOB_PREFIX,
            outputDomainLocation.blobStoreDataLocation().key());

    // Set output domain location and debug run in job.
    ctx =
        ctx.toBuilder()
            .setRequestInfo(
                ctx.requestInfo().toBuilder()
                    .putAllJobParameters(
                        combineJobParams(ctx.requestInfo().getJobParameters(), jobParams))
                    .build())
            .build();
    fakeNoiseApplierSupplier.setFakeNoiseApplier(new ConstantNoiseApplier(-3));

    JobResult jobResultProcessor = processor.get().process(ctx);

    assertThat(jobResultProcessor).isEqualTo(makeExpectedJobResult());
    assertThat(resultLogger.getMaterializedAggregationResults().getMaterializedAggregations())
        .containsExactly(
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(2), /* metric= */ 5, /* unnoisedMetric= */ 8L),
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(3), /* metric= */ -3, /* unnoisedMetric= */ 0L));
    assertThat(resultLogger.getMaterializedDebugAggregationResults().getMaterializedAggregations())
        .containsExactly(
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(1),
                /* metric= */ -1,
                /* unnoisedMetric= */ 2L,
                /* debugAnnotations= */ List.of(DebugBucketAnnotation.IN_REPORTS)),
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(2),
                /* metric= */ 5,
                /* unnoisedMetric= */ 8L,
                /* debugAnnotations= */ List.of(
                    DebugBucketAnnotation.IN_REPORTS, DebugBucketAnnotation.IN_DOMAIN)),
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(3),
                /* metric= */ -3,
                /* unnoisedMetric= */ 0L,
                /* debugAnnotations= */ List.of(DebugBucketAnnotation.IN_DOMAIN)));
  }

  @Test
  public void aggregate_withOutputDomain_noThresholding() throws Exception {
    writeOutputDomainAvroFile(
        outputDomainDirectory.resolve("output_domain_1.avro"),
        "1"); // 1 is in output domain, so no thresholding applies
    DataLocation outputDomainLocation = getOutputDomainLocation();
    ImmutableMap<String, String> jobParams =
        ImmutableMap.of(
            JOB_PARAM_OUTPUT_DOMAIN_BUCKET_NAME,
            outputDomainLocation.blobStoreDataLocation().bucket(),
            JOB_PARAM_OUTPUT_DOMAIN_BLOB_PREFIX,
            outputDomainLocation.blobStoreDataLocation().key());

    ctx =
        ctx.toBuilder()
            .setRequestInfo(
                ctx.requestInfo().toBuilder()
                    .putAllJobParameters(
                        combineJobParams(ctx.requestInfo().getJobParameters(), jobParams))
                    .build())
            .build();
    fakeNoiseApplierSupplier.setFakeNoiseApplier(new ConstantNoiseApplier(-3));

    JobResult jobResultProcessor = processor.get().process(ctx);

    assertThat(jobResultProcessor).isEqualTo(makeExpectedJobResult());
    assertThat(resultLogger.getMaterializedAggregationResults().getMaterializedAggregations())
        .containsExactly(
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(1), /* metric= */ -1, /* unnoisedMetric= */ 2L),
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(2), /* metric= */ 5, /* unnoisedMetric= */ 8L));
  }

  @Test
  public void aggregate_withOutputDomain_addKeys() throws Exception {
    writeOutputDomainAvroFile(outputDomainDirectory.resolve("output_domain_1.avro"), "3");
    writeOutputDomainAvroFile(outputDomainDirectory.resolve("output_domain_2.avro"), "1", "2");
    DataLocation outputDomainLocation = getOutputDomainLocation();
    ImmutableMap<String, String> jobParams =
        ImmutableMap.of(
            JOB_PARAM_OUTPUT_DOMAIN_BUCKET_NAME,
            outputDomainLocation.blobStoreDataLocation().bucket(),
            JOB_PARAM_OUTPUT_DOMAIN_BLOB_PREFIX,
            outputDomainLocation.blobStoreDataLocation().key());
    ctx =
        ctx.toBuilder()
            .setRequestInfo(
                ctx.requestInfo().toBuilder()
                    .putAllJobParameters(
                        combineJobParams(ctx.requestInfo().getJobParameters(), jobParams))
                    .build())
            .build();

    JobResult jobResultProcessor = processor.get().process(ctx);

    assertThat(jobResultProcessor).isEqualTo(makeExpectedJobResult());
    // Key 3 added as an extra key from the output domain.
    assertThat(resultLogger.getMaterializedAggregationResults().getMaterializedAggregations())
        .containsExactly(
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(1), /* metric= */ 2, /* unnoisedMetric= */ 2L),
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(2), /* metric= */ 8, /* unnoisedMetric= */ 8L),
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(3), /* metric= */ 0, /* unnoisedMetric= */ 0L));
  }

  @Test
  public void aggregate_withOutputDomain_addKeysAndExtra() throws Exception {
    writeOutputDomainAvroFile(outputDomainDirectory.resolve("output_domain_1.avro"), "3");
    writeOutputDomainAvroFile(
        outputDomainDirectory.resolve("output_domain_2.avro"),
        "2",
        "3"); // 3 is intentionally duplicate.
    DataLocation outputDomainLocation = getOutputDomainLocation();
    ImmutableMap<String, String> jobParams =
        ImmutableMap.of(
            JOB_PARAM_OUTPUT_DOMAIN_BUCKET_NAME,
            outputDomainLocation.blobStoreDataLocation().bucket(),
            JOB_PARAM_OUTPUT_DOMAIN_BLOB_PREFIX,
            outputDomainLocation.blobStoreDataLocation().key());
    ctx =
        ctx.toBuilder()
            .setRequestInfo(
                ctx.requestInfo().toBuilder()
                    .putAllJobParameters(
                        combineJobParams(ctx.requestInfo().getJobParameters(), jobParams))
                    .build())
            .build();

    JobResult jobResultProcessor = processor.get().process(ctx);

    assertThat(jobResultProcessor).isEqualTo(makeExpectedJobResult());
    // Key 3 added as an extra key from the output domain.
    // Key is filtered out because it is not in the output domain.
    assertThat(resultLogger.getMaterializedAggregationResults().getMaterializedAggregations())
        .containsExactly(
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(1), /* metric= */ 2, /* unnoisedMetric= */ 2L),
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(2), /* metric= */ 8, /* unnoisedMetric= */ 8L),
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(3), /* metric= */ 0, /* unnoisedMetric= */ 0L));
  }

  @Test
  public void aggregate_withOutputDomain_domainNotExistent() throws Exception {
    // Intentionally skipping the output domain generation here.
    DataLocation outputDomainLocation = getOutputDomainLocation();
    ImmutableMap<String, String> jobParams =
        ImmutableMap.of(
            JOB_PARAM_OUTPUT_DOMAIN_BUCKET_NAME,
            outputDomainLocation.blobStoreDataLocation().bucket(),
            JOB_PARAM_OUTPUT_DOMAIN_BLOB_PREFIX,
            outputDomainLocation.blobStoreDataLocation().key());
    ctx =
        ctx.toBuilder()
            .setRequestInfo(
                ctx.requestInfo().toBuilder()
                    .putAllJobParameters(
                        combineJobParams(ctx.requestInfo().getJobParameters(), jobParams))
                    .build())
            .build();

    AggregationJobProcessException ex =
        assertThrows(AggregationJobProcessException.class, () -> processor.get().process(ctx));

    assertThat(ex.getCode()).isEqualTo(INPUT_DATA_READ_FAILED);
    assertThat(ex.getMessage()).contains("Exception while reading domain input data.");
  }

  @Test
  public void aggregate_withOutputDomain_avroDomainNotReadable() throws Exception {
    // Intentionally skipping the output domain generation here.
    Path badDataShard = outputDomainDirectory.resolve("domain_bad.avro");
    writeOutputDomainTextFile(badDataShard, "bad shard");
    DataLocation outputDomainLocation = getOutputDomainLocation();
    ImmutableMap<String, String> jobParams =
        ImmutableMap.of(
            JOB_PARAM_OUTPUT_DOMAIN_BUCKET_NAME,
            outputDomainLocation.blobStoreDataLocation().bucket(),
            JOB_PARAM_OUTPUT_DOMAIN_BLOB_PREFIX,
            outputDomainLocation.blobStoreDataLocation().key());
    ctx =
        ctx.toBuilder()
            .setRequestInfo(
                ctx.requestInfo().toBuilder()
                    .putAllJobParameters(
                        combineJobParams(ctx.requestInfo().getJobParameters(), jobParams))
                    .build())
            .build();

    AggregationJobProcessException ex =
        assertThrows(AggregationJobProcessException.class, () -> processor.get().process(ctx));

    assertThat(ex.getCode()).isEqualTo(INPUT_DATA_READ_FAILED);
    assertThat(ex.getMessage()).contains("Exception while reading domain input data.");
  }

  @Test
  public void aggregate_withOutputDomain_textDomainNotReadable() throws Exception {
    outputDomainProcessorHelper.setAvroOutputDomainProcessor(false);
    Path badDataShard = outputDomainDirectory.resolve("domain_bad.txt");
    writeOutputDomainTextFile(badDataShard, "abcdabcdabcdabcdabcdabcdabcdabcd");
    DataLocation outputDomainLocation = getOutputDomainLocation();
    ImmutableMap<String, String> jobParams =
        ImmutableMap.of(
            JOB_PARAM_OUTPUT_DOMAIN_BUCKET_NAME,
            outputDomainLocation.blobStoreDataLocation().bucket(),
            JOB_PARAM_OUTPUT_DOMAIN_BLOB_PREFIX,
            outputDomainLocation.blobStoreDataLocation().key());
    ctx =
        ctx.toBuilder()
            .setRequestInfo(
                ctx.requestInfo().toBuilder()
                    .putAllJobParameters(
                        combineJobParams(ctx.requestInfo().getJobParameters(), jobParams))
                    .build())
            .build();

    AggregationJobProcessException ex =
        assertThrows(AggregationJobProcessException.class, () -> processor.get().process(ctx));

    assertThat(ex.getCode()).isEqualTo(INPUT_DATA_READ_FAILED);
    assertThat(ex.getMessage()).contains("Exception while reading domain input data.");
  }

  @Test
  public void aggregate_withNoise() throws Exception {
    fakeNoiseApplierSupplier.setFakeNoiseApplier(new ConstantNoiseApplier(10));

    JobResult jobResultProcessor = processor.get().process(ctx);

    assertThat(jobResultProcessor).isEqualTo(expectedJobResult);
    assertThat(resultLogger.getMaterializedAggregationResults().getMaterializedAggregations())
        .containsExactly(
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(1), /* metric= */ 12, /* unnoisedMetric= */ 2L),
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(2), /* metric= */ 18, /* unnoisedMetric= */ 8L));
  }

  @Test
  public void process_withValidationErrors() throws Exception {
    fakeValidator.setReportIdShouldReturnError(ImmutableSet.of(reportId1));
    // Since 1st report has validation errors, only facts in 2nd report are noised.
    JobResult jobResultProcessor = processor.get().process(ctx);

    JobResult expectedJobResult =
        this.expectedJobResult.toBuilder()
            .setResultInfo(
                resultInfoBuilder
                    .setReturnCode(AggregationWorkerReturnCode.SUCCESS_WITH_ERRORS.name())
                    .setReturnMessage(RESULT_SUCCESS_WITH_ERRORS_MESSAGE)
                    .setErrorSummary(
                        ErrorSummary.newBuilder()
                            .addAllErrorCounts(
                                ImmutableList.of(
                                    ErrorCount.newBuilder()
                                        .setCategory(ErrorCounter.DECRYPTION_ERROR.name())
                                        .setDescription(
                                            ErrorCounter.DECRYPTION_ERROR.getDescription())
                                        .setCount(1L)
                                        .build(),
                                    ErrorCount.newBuilder()
                                        .setCategory(ErrorCounter.NUM_REPORTS_WITH_ERRORS.name())
                                        .setDescription(
                                            ErrorCounter.NUM_REPORTS_WITH_ERRORS.getDescription())
                                        .setCount(1L)
                                        .build()))
                            .build())
                    .build())
            .build();
    assertThat(jobResultProcessor).isEqualTo(expectedJobResult);
    assertThat(resultLogger.getMaterializedAggregationResults().getMaterializedAggregations())
        .containsExactly(
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(1), /* metric= */ 1, /* unnoisedMetric= */ 1L),
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(2), /* metric= */ 8, /* unnoisedMetric= */ 8L));
  }

  @Test
  public void process_withValidationErrors_allReportsFail() throws Exception {
    FakePrivacyBudgetingServiceBridge fakePrivacyBudgetingServiceBridge =
        new FakePrivacyBudgetingServiceBridge();
    privacyBudgetingServiceBridge.setPrivacyBudgetingServiceBridgeImpl(
        fakePrivacyBudgetingServiceBridge);
    // Throw validation errors for all reports
    fakeValidator.setReportIdShouldReturnError(
        ImmutableSet.of(reportId1, reportId2, reportId3, reportId4));

    JobResult jobResultProcessor = processor.get().process(ctx);

    JobResult expectedJobResult =
        this.expectedJobResult.toBuilder()
            .setResultInfo(
                resultInfoBuilder
                    .setReturnCode(AggregationWorkerReturnCode.SUCCESS_WITH_ERRORS.name())
                    .setReturnMessage(RESULT_SUCCESS_WITH_ERRORS_MESSAGE)
                    .setErrorSummary(
                        ErrorSummary.newBuilder()
                            .addAllErrorCounts(
                                ImmutableList.of(
                                    ErrorCount.newBuilder()
                                        .setCategory(ErrorCounter.DECRYPTION_ERROR.name())
                                        .setDescription(
                                            ErrorCounter.DECRYPTION_ERROR.getDescription())
                                        .setCount(4L)
                                        .build(),
                                    ErrorCount.newBuilder()
                                        .setCategory(ErrorCounter.NUM_REPORTS_WITH_ERRORS.name())
                                        .setDescription(
                                            ErrorCounter.NUM_REPORTS_WITH_ERRORS.getDescription())
                                        .setCount(4L)
                                        .build()))
                            .build())
                    .build())
            .build();
    assertThat(jobResultProcessor).isEqualTo(expectedJobResult);
    assertThat(resultLogger.getMaterializedAggregationResults().getMaterializedAggregations())
        .isEmpty();
    // Check that no calls were made to PBS
    assertThat(fakePrivacyBudgetingServiceBridge.getLastBudgetsToConsumeSent()).isEmpty();
    assertThat(fakePrivacyBudgetingServiceBridge.getLastAttributionReportToSent()).isEmpty();
  }

  @Test
  public void process_inputReadFailedCodeWhenBadShardThrows() throws Exception {
    Path badDataShard = reportsDirectory.resolve("reports_bad.avro");
    Files.writeString(badDataShard, "Bad data", US_ASCII, WRITE, CREATE);

    AggregationJobProcessException ex =
        assertThrows(AggregationJobProcessException.class, () -> processor.get().process(ctx));
    assertThat(ex.getCode()).isEqualTo(INPUT_DATA_READ_FAILED);
    assertThat(ex.getMessage()).contains("Exception while reading reports input data.");
  }

  @Test
  public void process_outputWriteFailedCodeWhenResultLoggerThrows() {
    resultLogger.setShouldThrow(true);

    AggregationJobProcessException ex =
        assertThrows(AggregationJobProcessException.class, () -> processor.get().process(ctx));

    assertThat(ex.getCode()).isEqualTo(RESULT_WRITE_ERROR);
  }

  @Test
  public void process_decryptionKeyFetchFailedWithPermissionDeniedReason() {
    fakeDecryptionKeyService.setShouldThrow(true, ErrorReason.PERMISSION_DENIED);

    AggregationJobProcessException ex =
        assertThrows(AggregationJobProcessException.class, () -> processor.get().process(ctx));

    assertThat(ex.getCode()).isEqualTo(PERMISSION_ERROR);
  }

  @Test
  public void process_decryptionKeyFetchServiceUnavailable_throwsInternal() {
    fakeDecryptionKeyService.setShouldThrow(true, ErrorReason.KEY_SERVICE_UNAVAILABLE);

    AggregationJobProcessException ex =
        assertThrows(AggregationJobProcessException.class, () -> processor.get().process(ctx));

    assertThat(ex.getCode()).isEqualTo(INTERNAL_ERROR);
  }

  @Test
  public void process_decryptionKeyFetchFailedOtherReasons() throws Exception {
    fakeDecryptionKeyService.setShouldThrow(true);

    JobResult actualJobResult = processor.get().process(ctx);

    JobResult expectedJobResult =
        this.expectedJobResult.toBuilder()
            .setResultInfo(
                resultInfoBuilder
                    .setReturnCode(AggregationWorkerReturnCode.SUCCESS_WITH_ERRORS.name())
                    .setReturnMessage(RESULT_SUCCESS_WITH_ERRORS_MESSAGE)
                    .setErrorSummary(
                        ErrorSummary.newBuilder()
                            .addAllErrorCounts(
                                ImmutableList.of(
                                    ErrorCount.newBuilder()
                                        .setCategory(ErrorCounter.SERVICE_ERROR.name())
                                        .setDescription(ErrorCounter.SERVICE_ERROR.getDescription())
                                        .setCount(4L)
                                        .build(),
                                    ErrorCount.newBuilder()
                                        .setCategory(ErrorCounter.NUM_REPORTS_WITH_ERRORS.name())
                                        .setDescription(NUM_REPORTS_WITH_ERRORS.getDescription())
                                        .setCount(4L)
                                        .build()))
                            .build())
                    .build())
            .build();
    assertThat(actualJobResult).isEqualTo(expectedJobResult);
  }

  @Test
  public void process_errorCountExceedsThreshold_quitsEarly() throws Exception {
    ImmutableList<EncryptedReport> encryptedReports1 =
        ImmutableList.of(
            generateEncryptedReport(1, reportId1),
            generateEncryptedReport(2, reportId2),
            generateEncryptedReport(3, String.valueOf(UUID.randomUUID())),
            generateEncryptedReport(4, String.valueOf(UUID.randomUUID())),
            generateEncryptedReport(5, reportId3));
    ImmutableList<EncryptedReport> encryptedReports2 =
        ImmutableList.of(
            generateEncryptedReport(6, reportId4),
            generateEncryptedReport(7, String.valueOf(UUID.randomUUID())),
            generateEncryptedReport(8, String.valueOf(UUID.randomUUID())),
            generateEncryptedReport(9, String.valueOf(UUID.randomUUID())),
            generateEncryptedReport(10, String.valueOf(UUID.randomUUID())));
    writeReports(reportsDirectory.resolve("reports_1.avro"), encryptedReports1);
    writeReports(reportsDirectory.resolve("reports_2.avro"), encryptedReports2);
    FakePrivacyBudgetingServiceBridge fakePrivacyBudgetingServiceBridge =
        new FakePrivacyBudgetingServiceBridge();
    privacyBudgetingServiceBridge.setPrivacyBudgetingServiceBridgeImpl(
        fakePrivacyBudgetingServiceBridge);
    fakeValidator.setReportIdShouldReturnError(
        ImmutableSet.of(reportId1, reportId2, reportId3, reportId4));
    ImmutableMap<String, String> jobParams =
        ImmutableMap.of(JOB_PARAM_REPORT_ERROR_THRESHOLD_PERCENTAGE, "20");
    ctx =
        ctx.toBuilder()
            .setRequestInfo(
                ctx.requestInfo().toBuilder()
                    .putAllJobParameters(
                        combineJobParams(ctx.requestInfo().getJobParameters(), jobParams))
                    .build())
            .build();

    JobResult actualJobResult = processor.get().process(ctx);

    // Job quits on error count 4 > threshold 2 (20% threshold of 10 reports)
    JobResult expectedJobResult =
        this.expectedJobResult.toBuilder()
            .setResultInfo(
                resultInfoBuilder
                    .setReturnCode(
                        AggregationWorkerReturnCode.REPORTS_WITH_ERRORS_EXCEEDED_THRESHOLD.name())
                    .setReturnMessage(RESULT_REPORTS_WITH_ERRORS_EXCEEDED_THRESHOLD_MESSAGE)
                    .setErrorSummary(
                        ErrorSummary.newBuilder()
                            .addAllErrorCounts(
                                ImmutableList.of(
                                    ErrorCount.newBuilder()
                                        .setCategory(ErrorCounter.DECRYPTION_ERROR.name())
                                        .setDescription(
                                            ErrorCounter.DECRYPTION_ERROR.getDescription())
                                        .setCount(4L)
                                        .build(),
                                    ErrorCount.newBuilder()
                                        .setCategory(ErrorCounter.NUM_REPORTS_WITH_ERRORS.name())
                                        .setDescription(NUM_REPORTS_WITH_ERRORS.getDescription())
                                        .setCount(4L)
                                        .build()))
                            .build())
                    .build())
            .build();
    assertThat(actualJobResult).isEqualTo(expectedJobResult);
    assertThat(fakePrivacyBudgetingServiceBridge.getLastBudgetsToConsumeSent()).isEmpty();
    assertFalse(resultLogger.hasLogged());
  }

  @Test
  public void process_errorCountWithinThreshold_succeedsWithErrors() throws Exception {
    ImmutableList<EncryptedReport> encryptedReports1 =
        ImmutableList.of(
            generateEncryptedReport(1, reportId1),
            generateEncryptedReport(2, reportId2),
            generateEncryptedReport(3, String.valueOf(UUID.randomUUID())),
            generateEncryptedReport(4, String.valueOf(UUID.randomUUID())),
            generateEncryptedReport(5, reportId3));
    ImmutableList<EncryptedReport> encryptedReports2 =
        ImmutableList.of(
            generateEncryptedReport(6, reportId4),
            generateEncryptedReport(7, String.valueOf(UUID.randomUUID())),
            generateEncryptedReport(8, String.valueOf(UUID.randomUUID())),
            generateEncryptedReport(9, String.valueOf(UUID.randomUUID())),
            generateEncryptedReport(10, String.valueOf(UUID.randomUUID())));
    writeReports(reportsDirectory.resolve("reports_1.avro"), encryptedReports1);
    writeReports(reportsDirectory.resolve("reports_2.avro"), encryptedReports2);

    fakeValidator.setReportIdShouldReturnError(
        ImmutableSet.of(reportId1, reportId2, reportId3, reportId4));
    ImmutableMap<String, String> jobParams =
        ImmutableMap.of(JOB_PARAM_REPORT_ERROR_THRESHOLD_PERCENTAGE, "50.0");
    ctx =
        ctx.toBuilder()
            .setRequestInfo(
                ctx.requestInfo().toBuilder()
                    .putAllJobParameters(
                        combineJobParams(ctx.requestInfo().getJobParameters(), jobParams))
                    .build())
            .build();

    JobResult actualJobResult = processor.get().process(ctx);

    // Job succeeds because error count 4 < threshold 5
    JobResult expectedJobResult =
        this.expectedJobResult.toBuilder()
            .setResultInfo(
                resultInfoBuilder
                    .setReturnCode(AggregationWorkerReturnCode.SUCCESS_WITH_ERRORS.name())
                    .setReturnMessage(RESULT_SUCCESS_WITH_ERRORS_MESSAGE)
                    .setErrorSummary(
                        ErrorSummary.newBuilder()
                            .addAllErrorCounts(
                                ImmutableList.of(
                                    ErrorCount.newBuilder()
                                        .setCategory(ErrorCounter.DECRYPTION_ERROR.name())
                                        .setDescription(
                                            ErrorCounter.DECRYPTION_ERROR.getDescription())
                                        .setCount(4L)
                                        .build(),
                                    ErrorCount.newBuilder()
                                        .setCategory(ErrorCounter.NUM_REPORTS_WITH_ERRORS.name())
                                        .setDescription(NUM_REPORTS_WITH_ERRORS.getDescription())
                                        .setCount(4L)
                                        .build()))
                            .build())
                    .build())
            .build();
    assertThat(actualJobResult).isEqualTo(expectedJobResult);
    assertThat(resultLogger.getMaterializedAggregationResults().getMaterializedAggregations())
        .containsExactly(
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(3), /* metric= */ 9, /* unnoisedMetric= */ 9L),
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(4), /* metric= */ 16, /* unnoisedMetric= */ 16L),
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(7), /* metric= */ 49, /* unnoisedMetric= */ 49L),
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(8), /* metric= */ 64, /* unnoisedMetric= */ 64L),
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(9), /* metric= */ 81, /* unnoisedMetric= */ 81L),
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(10),
                /* metric= */ 100,
                /* unnoisedMetric= */ 100L));
  }

  @Test
  public void processingWithWrongSharedInfo() throws Exception {
    String keyId = UUID.randomUUID().toString();
    Report report = FakeReportGenerator.generateWithParam(1, /* reportVersion */ LATEST_VERSION);
    // Encrypt with a different sharedInfo than what is provided with the report so that decryption
    // fails
    String sharedInfoForEncryption = "foobarbaz";
    String sharedInfoWithReport =
        sharedInfoSerdes.reverse().convert(Optional.of(report.sharedInfo()));
    ByteSource reportBytes =
        fakeDecryptionKeyService.generateCiphertext(
            keyId,
            payloadSerdes.reverse().convert(Optional.of(report.payload())),
            sharedInfoForEncryption);
    EncryptedReport encryptedReport =
        EncryptedReport.builder()
            .setPayload(reportBytes)
            .setKeyId(keyId)
            .setSharedInfo(sharedInfoWithReport)
            .build();
    encryptedReports1 = ImmutableList.of(encryptedReport, encryptedReport);
    // 2 shards of same contents.
    writeReports(reportsDirectory.resolve("reports_1.avro"), encryptedReports1);
    writeReports(reportsDirectory.resolve("reports_2.avro"), encryptedReports1);
    writeOutputDomainAvroFile(outputDomainDirectory.resolve("output_domain_1.avro"), "1");
    DataLocation outputDomainLocation = getOutputDomainLocation();
    ImmutableMap<String, String> jobParams =
        ImmutableMap.of(
            JOB_PARAM_OUTPUT_DOMAIN_BUCKET_NAME,
            outputDomainLocation.blobStoreDataLocation().bucket(),
            JOB_PARAM_OUTPUT_DOMAIN_BLOB_PREFIX,
            outputDomainLocation.blobStoreDataLocation().key());
    ctx =
        ctx.toBuilder()
            .setRequestInfo(
                ctx.requestInfo().toBuilder()
                    .putAllJobParameters(
                        combineJobParams(ctx.requestInfo().getJobParameters(), jobParams))
                    .build())
            .build();

    JobResult jobResultProcessor = processor.get().process(ctx);

    assertThat(resultLogger.getMaterializedAggregationResults().getMaterializedAggregations())
        .containsExactly(
            AggregatedFact.create(/* bucket= */ createBucketFromInt(1), /* metric= */ 0, 0L));
  }

  @Test
  public void aggregate_withPrivacyBudgeting_noBudget() throws Exception {
    FakePrivacyBudgetingServiceBridge fakePrivacyBudgetingServiceBridge =
        new FakePrivacyBudgetingServiceBridge();
    // No budget given, i.e. all the budgets are depleted for this test.
    privacyBudgetingServiceBridge.setPrivacyBudgetingServiceBridgeImpl(
        fakePrivacyBudgetingServiceBridge);

    AggregationJobProcessException ex =
        assertThrows(AggregationJobProcessException.class, () -> processor.get().process(ctx));
    assertThat(ex.getCode()).isEqualTo(PRIVACY_BUDGET_EXHAUSTED);
  }

  @Test
  public void aggregate_withPrivacyBudgeting() throws Exception {
    FakePrivacyBudgetingServiceBridge fakePrivacyBudgetingServiceBridge =
        new FakePrivacyBudgetingServiceBridge();
    // privacy budget key generated from encryptedReports1
    fakePrivacyBudgetingServiceBridge.setPrivacyBudget(
        PrivacyBudgetUnit.create(
            "feb6671c7739adeb5140f2af92bb345545e8f16e1761292ac871eaae7904393f",
            Instant.ofEpochMilli(0)),
        1);
    // privacy budget key generated from encryptedReports2
    fakePrivacyBudgetingServiceBridge.setPrivacyBudget(
        PrivacyBudgetUnit.create(
            "455919cec4e33a989ebbebc13084da12209dc920620d8bd821a48c0e721bc631",
            Instant.ofEpochMilli(0)),
        1);
    privacyBudgetingServiceBridge.setPrivacyBudgetingServiceBridgeImpl(
        fakePrivacyBudgetingServiceBridge);

    JobResult jobResultProcessor = processor.get().process(ctx);

    assertThat(jobResultProcessor).isEqualTo(expectedJobResult);
    assertThat(resultLogger.getMaterializedAggregationResults().getMaterializedAggregations())
        .containsExactly(
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(1), /* metric= */ 2, /* unnoisedMetric= */ 2L),
            AggregatedFact.create(
                /* bucket= */ createBucketFromInt(2), /* metric= */ 8, /* unnoisedMetric= */ 8L));
    // Check that the right attributionReportTo and debugPrivacyBudgetLimit were sent to the bridge
    assertThat(fakePrivacyBudgetingServiceBridge.getLastAttributionReportToSent())
        .hasValue(ctx.requestInfo().getJobParameters().get(JOB_PARAM_ATTRIBUTION_REPORT_TO));
  }

  @Test
  public void aggregate_withPrivacyBudgeting_unauthenticatedException_failJob() throws Exception {
    FakePrivacyBudgetingServiceBridge fakePrivacyBudgetingServiceBridge =
        new FakePrivacyBudgetingServiceBridge();

    fakePrivacyBudgetingServiceBridge.setException(
        new PrivacyBudgetingServiceBridgeException(
            StatusCode.PRIVACY_BUDGET_CLIENT_UNAUTHENTICATED, new IllegalStateException("fake")));
    privacyBudgetingServiceBridge.setPrivacyBudgetingServiceBridgeImpl(
        fakePrivacyBudgetingServiceBridge);

    AggregationJobProcessException ex =
        assertThrows(AggregationJobProcessException.class, () -> processor.get().process(ctx));
    assertThat(ex.getCode()).isEqualTo(PRIVACY_BUDGET_AUTHENTICATION_ERROR);
  }

  @Test
  public void aggregate_withPrivacyBudgeting_unauthorizedException_failJob() throws Exception {
    FakePrivacyBudgetingServiceBridge fakePrivacyBudgetingServiceBridge =
        new FakePrivacyBudgetingServiceBridge();

    fakePrivacyBudgetingServiceBridge.setException(
        new PrivacyBudgetingServiceBridgeException(
            StatusCode.PRIVACY_BUDGET_CLIENT_UNAUTHORIZED, new IllegalStateException("fake")));
    privacyBudgetingServiceBridge.setPrivacyBudgetingServiceBridgeImpl(
        fakePrivacyBudgetingServiceBridge);

    AggregationJobProcessException ex =
        assertThrows(AggregationJobProcessException.class, () -> processor.get().process(ctx));
    assertThat(ex.getCode()).isEqualTo(PRIVACY_BUDGET_AUTHORIZATION_ERROR);
  }

  @Test
  public void aggregate_withPrivacyBudgeting_oneBudgetMissing() throws Exception {
    FakePrivacyBudgetingServiceBridge fakePrivacyBudgetingServiceBridge =
        new FakePrivacyBudgetingServiceBridge();
    fakePrivacyBudgetingServiceBridge.setPrivacyBudget(
        PrivacyBudgetUnit.create("1", Instant.ofEpochMilli(0)), 1);
    // Missing budget for the second report.
    privacyBudgetingServiceBridge.setPrivacyBudgetingServiceBridgeImpl(
        fakePrivacyBudgetingServiceBridge);

    AggregationJobProcessException ex =
        assertThrows(AggregationJobProcessException.class, () -> processor.get().process(ctx));
    assertThat(ex.getCode()).isEqualTo(PRIVACY_BUDGET_EXHAUSTED);
  }

  /**
   * Test that the worker fails the job if an exception occurs when the reports bucket is
   * nonexistent.
   */
  @Test
  public void aggregate_withNonExistentBucket() throws Exception {
    Path nonExistentReportsDirectory =
        testWorkingDir.getRoot().toPath().resolve("nonExistentBucket");
    ctx =
        ctx.toBuilder()
            .setRequestInfo(
                ctx.requestInfo().toBuilder()
                    .setInputDataBucketName(nonExistentReportsDirectory.toAbsolutePath().toString())
                    .setInputDataBlobPrefix("")
                    .build())
            .build();
    // TODO(b/258078789): Passing nonexistent reports folder should throw
    // TODO(b/258082317): Add assertion on return message.
    AggregationJobProcessException ex =
        assertThrows(AggregationJobProcessException.class, () -> processor.get().process(ctx));
    assertThat(ex.getCode()).isEqualTo(INPUT_DATA_READ_FAILED);
  }

  /**
   * Test that the worker fails the job if an exception occurs when the report file path is
   * nonexistent.
   */
  @Test
  public void aggregate_withNonExistentReportFile() throws Exception {
    ctx =
        ctx.toBuilder()
            .setRequestInfo(
                ctx.requestInfo().toBuilder()
                    .setInputDataBucketName(reportsDirectory.toAbsolutePath().toString())
                    .setInputDataBlobPrefix("nonExistentReport.avro")
                    .build())
            .build();
    AggregationJobProcessException ex =
        assertThrows(AggregationJobProcessException.class, () -> processor.get().process(ctx));
    assertThat(ex.getCode()).isEqualTo(INPUT_DATA_READ_FAILED);
    assertThat(ex.getMessage()).contains("No report shards found for location");
  }

  @Test
  public void aggregate_withDebugRunAndPrivacyBudgetFailure_succeedsWithErrorCode()
      throws Exception {
    ImmutableMap<String, String> jobParams = ImmutableMap.of(JOB_PARAM_DEBUG_RUN, "true");
    // Set debug run params in job.
    ctx =
        ctx.toBuilder()
            .setRequestInfo(
                ctx.requestInfo().toBuilder()
                    .putAllJobParameters(
                        combineJobParams(ctx.requestInfo().getJobParameters(), jobParams))
                    .build())
            .build();

    FakePrivacyBudgetingServiceBridge fakePrivacyBudgetingServiceBridge =
        new FakePrivacyBudgetingServiceBridge();
    // Privacy Budget failure via thrown exception
    fakePrivacyBudgetingServiceBridge.setShouldThrow();
    fakePrivacyBudgetingServiceBridge.setPrivacyBudget(
        PrivacyBudgetUnit.create("1", Instant.ofEpochMilli(0)), 1);
    // Missing budget for the second report.
    privacyBudgetingServiceBridge.setPrivacyBudgetingServiceBridgeImpl(
        fakePrivacyBudgetingServiceBridge);
    fakeValidator.setNextShouldReturnError(ImmutableList.of(false, false, false, false).iterator());

    JobResult result = processor.get().process(ctx);

    // Return code should be SUCCESS, return message should match the would-be error
    assertThat(result.resultInfo().getReturnCode())
        .isEqualTo(AggregationWorkerReturnCode.DEBUG_SUCCESS_WITH_PRIVACY_BUDGET_ERROR.name());
  }

  /** Test that worker completes with success if debug run despite Privacy Budget exhausted */
  @Test
  public void aggregateDebug_withPrivacyBudgetExhausted() throws Exception {
    ImmutableMap<String, String> jobParams = ImmutableMap.of(JOB_PARAM_DEBUG_RUN, "true");
    // Set debug run params in job.
    ctx =
        ctx.toBuilder()
            .setRequestInfo(
                ctx.requestInfo().toBuilder()
                    .putAllJobParameters(
                        combineJobParams(ctx.requestInfo().getJobParameters(), jobParams))
                    .build())
            .build();

    FakePrivacyBudgetingServiceBridge fakePrivacyBudgetingServiceBridge =
        new FakePrivacyBudgetingServiceBridge();
    fakePrivacyBudgetingServiceBridge.setPrivacyBudget(
        PrivacyBudgetUnit.create("1", Instant.ofEpochMilli(0)), 1);
    // Missing budget for the second report.
    privacyBudgetingServiceBridge.setPrivacyBudgetingServiceBridgeImpl(
        fakePrivacyBudgetingServiceBridge);
    fakeValidator.setNextShouldReturnError(ImmutableList.of(false, false, false, false).iterator());

    JobResult result = processor.get().process(ctx);

    // Return code should be SUCCESS, return message should match the would-be error
    assertThat(result.resultInfo().getReturnCode())
        .isEqualTo(AggregationWorkerReturnCode.DEBUG_SUCCESS_WITH_PRIVACY_BUDGET_EXHAUSTED.name());
  }

  private void writeOutputDomainTextFile(Path outputDomainPath, String... keys) throws IOException {
    Files.write(outputDomainPath, ImmutableList.copyOf(keys), US_ASCII, WRITE, CREATE);
  }

  private void writeOutputDomainAvroFile(Path domainFile, String... keys) throws IOException {
    try (OutputStream outputAvroStream = Files.newOutputStream(domainFile, CREATE);
        AvroOutputDomainWriter outputDomainWriter = domainWriterFactory.create(outputAvroStream)) {
      ImmutableList<AvroOutputDomainRecord> domain =
          Arrays.stream(keys)
              .map(NumericConversions::createBucketFromString)
              .map(AvroOutputDomainRecord::create)
              .collect(toImmutableList());
      outputDomainWriter.writeRecords(ImmutableList.of(), domain);
    }
  }

  private JobResult makeExpectedJobResult() {
    // Can't use FakeJobResultGenerator since values are different
    return JobResult.builder()
        .setJobKey(ctx.jobKey())
        .setResultInfo(resultInfoBuilder.build())
        .build();
  }

  private void writeReports(Path reportsPath, ImmutableList<EncryptedReport> encryptedReports)
      throws IOException {
    try (OutputStream avroStream = Files.newOutputStream(reportsPath, CREATE, TRUNCATE_EXISTING);
        AvroReportWriter reportWriter = reportWriterFactory.create(avroStream)) {
      reportWriter.writeRecords(
          /* metadata= */ ImmutableList.of(),
          encryptedReports.stream().map(avroConverter.reverse()).collect(toImmutableList()));
    }
  }

  private DataLocation getOutputDomainLocation() {
    return DataLocation.ofBlobStoreDataLocation(
        BlobStoreDataLocation.create(
            /* bucket= */ outputDomainDirectory.toAbsolutePath().toString(), /* key= */ ""));
  }

  /**
   * Proxy implementation for the privacy budgeting service that passes the call to the wrapped
   * budgeting bridge: this enables the testing to dynamically swap out implementations instead of
   * just statically assembling the implementation with Acai.
   */
  private static class ProxyPrivacyBudgetingServiceBridge implements PrivacyBudgetingServiceBridge {

    private PrivacyBudgetingServiceBridge wrappedImpl;

    private void setPrivacyBudgetingServiceBridgeImpl(PrivacyBudgetingServiceBridge impl) {
      wrappedImpl = impl;
    }

    @Override
    public ImmutableList<PrivacyBudgetUnit> consumePrivacyBudget(
        ImmutableList<PrivacyBudgetUnit> budgetsToConsume, String attributionReportTo)
        throws PrivacyBudgetingServiceBridgeException {
      return wrappedImpl.consumePrivacyBudget(budgetsToConsume, attributionReportTo);
    }
  }

  private ImmutableMap<String, String> combineJobParams(
      Map<String, String> currentJobParams, Map<String, String> additionalJobParams) {
    Map<String, String> map = Maps.newHashMap();
    map.putAll(currentJobParams);
    map.putAll(additionalJobParams);
    return ImmutableMap.copyOf(map);
  }

  private static class OutputDomainProcessorHelper {
    boolean isAvroOutputDomainProcessor = true;

    void setAvroOutputDomainProcessor(Boolean flag) {
      isAvroOutputDomainProcessor = flag;
    }

    boolean getAvroOutputDomainProcessor() {
      return isAvroOutputDomainProcessor;
    }
  }

  // TODO: these setup steps could be consolidated with the SimpleAggregationProcessorTest TestEnv.
  private static final class TestEnv extends AbstractModule {

    OutputDomainProcessorHelper helper = new OutputDomainProcessorHelper();

    @Override
    protected void configure() {
      bind(ObjectMapper.class).to(TimeObjectMapper.class);

      // Report reading
      install(new FSBlobStorageClientModule());
      bind(FileSystem.class).toInstance(FileSystems.getDefault());

      // decryption
      bind(FakeDecryptionKeyService.class).in(TestScoped.class);
      bind(DecryptionKeyService.class).to(FakeDecryptionKeyService.class);
      install(new HybridDecryptionModule());
      bind(RecordDecrypter.class).to(DeserializingReportDecrypter.class);
      bind(PayloadSerdes.class).to(CborPayloadSerdes.class);

      // report validation.
      bind(FakeValidator.class).in(TestScoped.class);
      Multibinder<ReportValidator> reportValidatorMultibinder =
          Multibinder.newSetBinder(binder(), ReportValidator.class);
      reportValidatorMultibinder.addBinding().to(FakeValidator.class);
      reportValidatorMultibinder.addBinding().to(ReportVersionValidator.class);

      // noising
      bind(FakeNoiseApplierSupplier.class).in(TestScoped.class);
      bind(NoisedAggregationRunner.class).to(NoisedAggregationRunnerImpl.class);

      // loggers.
      bind(InMemoryResultLogger.class).in(TestScoped.class);
      bind(ResultLogger.class).to(InMemoryResultLogger.class);

      // Stopwatches
      bind(StopwatchExporter.class).to(NoOpStopwatchExporter.class);

      // Privacy budgeting
      bind(ProxyPrivacyBudgetingServiceBridge.class).in(TestScoped.class);
      bind(PrivacyBudgetingServiceBridge.class).to(ProxyPrivacyBudgetingServiceBridge.class);

      // Privacy parameters
      bind(Distribution.class)
          .annotatedWith(NoisingDistribution.class)
          .toInstance(Distribution.LAPLACE);
      bind(double.class).annotatedWith(NoisingEpsilon.class).toInstance(0.1);
      bind(long.class).annotatedWith(NoisingL1Sensitivity.class).toInstance(4L);
      bind(double.class).annotatedWith(NoisingDelta.class).toInstance(5.00);

      // TODO(b/227210339) Add a test with false value for domainOptional
      boolean domainOptional = true;
      bind(Boolean.class).annotatedWith(DomainOptional.class).toInstance(domainOptional);
      bind(Boolean.class).annotatedWith(EnableThresholding.class).toInstance(domainOptional);

      // Otel collector
      install(new OtlpJsonLoggingOTelConfigurationModule());
      bind(Boolean.class).annotatedWith(EnableStackTraceInResponse.class).toInstance(true);
      bind(Integer.class).annotatedWith(MaxDepthOfStackTrace.class).toInstance(3);
      bind(double.class).annotatedWith(ReportErrorThresholdPercentage.class).toInstance(10.0);
      bind(OutputDomainProcessorHelper.class).toInstance(helper);
    }

    @Provides
    OutputDomainProcessor provideDomainProcess(
        @BlockingThreadPool ListeningExecutorService blockingThreadPool,
        @NonBlockingThreadPool ListeningExecutorService nonBlockingThreadPool,
        BlobStorageClient blobStorageClient,
        StopwatchRegistry stopwatchRegistry,
        AvroOutputDomainReaderFactory avroOutputDomainReaderFactory) {
      return helper.getAvroOutputDomainProcessor()
          ? new AvroOutputDomainProcessor(
              blockingThreadPool,
              nonBlockingThreadPool,
              blobStorageClient,
              avroOutputDomainReaderFactory,
              stopwatchRegistry)
          : new TextOutputDomainProcessor(
              blockingThreadPool, nonBlockingThreadPool, blobStorageClient, stopwatchRegistry);
    }

    @Provides
    Supplier<NoiseApplier> provideNoiseApplierSupplier(
        FakeNoiseApplierSupplier fakeNoiseApplierSupplier) {
      return fakeNoiseApplierSupplier;
    }

    @Provides
    Supplier<PrivacyParameters> providePrivacyParamConfig(PrivacyParametersSupplier supplier) {
      return () -> supplier.get().toBuilder().setDelta(1e-5).build();
    }

    @Provides
    @Threshold
    Supplier<Double> provideThreshold() {
      return () -> 0.0;
    }

    @Provides
    Clock provideClock() {
      return Clock.fixed(FIXED_TIME, ZoneId.systemDefault());
    }

    @Provides
    Ticker provideTimingTicker() {
      return Ticker.systemTicker();
    }

    @Provides
    @NonBlockingThreadPool
    ListeningExecutorService provideNonBlockingThreadPool() {
      return newDirectExecutorService();
    }

    @Provides
    @BlockingThreadPool
    ListeningExecutorService provideBlockingThreadPool() {
      return newDirectExecutorService();
    }

    @Provides
    AggregationEngine provideAggregationEngine() {
      return AggregationEngine.create();
    }
  }
}
