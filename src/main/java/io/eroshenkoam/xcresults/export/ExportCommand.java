package io.eroshenkoam.xcresults.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.commons.io.FileUtils;
import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static io.eroshenkoam.xcresults.util.ParseUtil.parseDate;

@CommandLine.Command(
        name = "export", mixinStandardHelpOptions = true,
        description = "Export XC test results to json with attachments"
)
public class ExportCommand implements Runnable {

    private static final String ACTIONS = "actions";
    private static final String ACTION_RESULT = "actionResult";

    private static final String RUN_DESTINATION = "runDestination";
    private static final String START_TIME = "startedTime";

    private static final String SUMMARIES = "summaries";
    private static final String TESTABLE_SUMMARIES = "testableSummaries";

    private static final String TESTS = "tests";
    private static final String SUBTESTS = "subtests";

    private static final String ACTIVITY_SUMMARIES = "activitySummaries";
    private static final String SUBACTIVITIES = "subactivities";

    private static final String ATTACHMENTS = "attachments";

    private static final String FILENAME = "filename";
    private static final String PAYLOAD_REF = "payloadRef";

    private static final String SUMMARY_REF = "summaryRef";

    private static final String SUITE = "suite";

    private static final String ID = "id";
    private static final String TYPE = "_type";
    private static final String NAME = "_name";
    private static final String VALUE = "_value";
    private static final String VALUES = "_values";
    private static final String DISPLAY_NAME = "displayName";
    private static final String TARGET_NAME = "targetName";

    private static final String TEST_REF = "testsRef";

    private final ObjectMapper mapper = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    @CommandLine.Option(
            names = {"--format"},
            description = "Export format (json, allure2)"
    )
    protected ExportFormat format = ExportFormat.allure2;

    @CommandLine.Parameters(
            index = "0",
            description = "The directories with *.xcresults"
    )
    protected Path inputPath;

    @CommandLine.Parameters(
            index = "1",
            description = "Export output directory"
    )
    protected Path outputPath;

    @Override
    public void run() {
        try {
            runUnsafe();
        } catch (Exception e) {
            e.printStackTrace(System.out);
        }
    }

    private void runUnsafe() throws Exception {
        final JsonNode node = readSummary();
        if (Files.exists(outputPath)) {
            System.out.println("Delete existing output directory...");
            FileUtils.deleteDirectory(outputPath.toFile());
        }
        Files.createDirectories(outputPath);

        final Map<String, ExportMeta> testRefIds = new HashMap<>();
        System.out.println("NODE: ");
        System.out.println(node.toString());
        if (Objects.nonNull(node)) {
            System.out.println("Node NOT Null");
        } else {
            System.out.println("Node Null");
        }
        for (JsonNode action : node.get(ACTIONS).get(VALUES)) {
            if (action.get(ACTION_RESULT).has(TEST_REF)) {
                final ExportMeta meta = new ExportMeta();
                if (action.has(RUN_DESTINATION)) {
                    meta.label(RUN_DESTINATION, action.get(RUN_DESTINATION).get(DISPLAY_NAME).get(VALUE).asText());
                }
                if (action.has(START_TIME)) {
                    meta.setStart(parseDate(action.get(START_TIME).get(VALUE).textValue()));
                }
                testRefIds.put(action.get(ACTION_RESULT).get(TEST_REF).get(ID).get(VALUE).asText(), meta);
            }
        }

        final Map<JsonNode, ExportMeta> testSummaries = new HashMap<>();
        testRefIds.forEach((testRefId, meta) -> {
            final JsonNode testRef = getReference(testRefId);
            for (JsonNode summary : testRef.get(SUMMARIES).get(VALUES)) {
                for (JsonNode testableSummary : summary.get(TESTABLE_SUMMARIES).get(VALUES)) {
                    final ExportMeta testMeta = getTestMeta(meta, testableSummary);
                    for (JsonNode test : testableSummary.get(TESTS).get(VALUES)) {
                        getTestSummaries(test).forEach(testSummary -> {
                            testSummaries.put(testSummary, testMeta);
                        });
                    }
                }
            }
        });

        System.out.println(String.format("Export information about %s test summaries...", testSummaries.size()));
        final Map<String, String> attachmentsRefs = new HashMap<>();
        testSummaries.forEach((testSummary, meta) -> {
            exportTestSummary(meta, testSummary);
            if (testSummary.has(ACTIVITY_SUMMARIES)) {
                for (final JsonNode activity : testSummary.get(ACTIVITY_SUMMARIES).get(VALUES)) {
                    attachmentsRefs.putAll(getAttachmentRefs(activity));
                }
            }
        });
        System.out.println(String.format("Export information about %s attachments...", attachmentsRefs.size()));
        for (Map.Entry<String, String> attachment : attachmentsRefs.entrySet()) {
            final Path attachmentPath = outputPath.resolve(attachment.getKey());
            final String attachmentRef = attachment.getValue();
            exportReference(attachmentRef, attachmentPath);
        }
    }

    private ExportMeta getTestMeta(final ExportMeta meta, final JsonNode testableSummary) {
        final ExportMeta exportMeta = new ExportMeta();
        exportMeta.setStart(meta.getStart());
        meta.getLabels().forEach(exportMeta::label);
        exportMeta.label(SUITE, testableSummary.get(TARGET_NAME).get(VALUE).asText());
        return exportMeta;
    }

    private void exportTestSummary(final ExportMeta meta, final JsonNode testSummary) {
        Path testSummaryPath = null;
        Object formattedResult = null;
        final String uuid = UUID.randomUUID().toString();
        switch (format) {
            case json: {
                final String testSummaryFilename = String.format("%s.json", uuid);
                testSummaryPath = outputPath.resolve(testSummaryFilename);
                formattedResult = new JsonExportFormatter().format(meta, testSummary);
                break;
            }
            case allure2: {
                final String testSummaryFilename = String.format("%s-result.json", uuid);
                testSummaryPath = outputPath.resolve(testSummaryFilename);
                formattedResult = new Allure2ExportFormatter().format(meta, testSummary);
                break;
            }
        }
        try {
            if (Objects.nonNull(formattedResult)) {
                mapper.writeValue(testSummaryPath.toFile(), formattedResult);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, String> getAttachmentRefs(final JsonNode test) {
        final Map<String, String> refs = new HashMap<>();
        if (test.has(ATTACHMENTS)) {
            for (final JsonNode attachment : test.get(ATTACHMENTS).get(VALUES)) {
                if (attachment.has(PAYLOAD_REF)) {
                    final String fileName = attachment.get(FILENAME).get(VALUE).asText();
                    final String attachmentRef = attachment.get(PAYLOAD_REF).get(ID).get(VALUE).asText();
                    refs.put(fileName, attachmentRef);
                }
            }
        }
        if (test.has(SUBACTIVITIES)) {
            for (final JsonNode subActivity : test.get(SUBACTIVITIES).get(VALUES)) {
                refs.putAll(getAttachmentRefs(subActivity));
            }
        }
        return refs;
    }

    private List<JsonNode> getTestSummaries(final JsonNode test) {
        final List<JsonNode> summaries = new ArrayList<>();
        if (test.has(SUMMARY_REF)) {
            final String ref = test.get(SUMMARY_REF).get(ID).get(VALUE).asText();
            summaries.add(getReference(ref));
        } else {
            if (test.has(TYPE) && test.get(TYPE).get(NAME).textValue().equals("ActionTestMetadata")) {
                summaries.add(test);
            }
        }

        if (test.has(SUBTESTS)) {
            for (final JsonNode subTest : test.get(SUBTESTS).get(VALUES)) {
                summaries.addAll(getTestSummaries(subTest));
            }
        }
        return summaries;
    }

    private JsonNode readSummary() {
        final ProcessBuilder builder = new ProcessBuilder();
        System.out.println(inputPath.toAbsolutePath().toString());
        builder.command(
                "xcrun",
                "xcresulttool",
                "get",
                "--format", "json",
                "--path", inputPath.toAbsolutePath().toString()
        );
        JsonNode node = readProcessOutput(builder);
        System.out.println(node);
        return node;
    }

    private JsonNode getReference(final String id) {
        final ProcessBuilder builder = new ProcessBuilder();
        builder.command(
                "xcrun",
                "xcresulttool",
                "get",
                "--format", "json",
                "--path", inputPath.toAbsolutePath().toString(),
                "--id", id
        );
        return readProcessOutput(builder);
    }

    private void exportReference(final String id, final Path output) {
        final ProcessBuilder builder = new ProcessBuilder();
        builder.command(
                "xcrun",
                "xcresulttool",
                "export",
                "--type", "file",
                "--path", inputPath.toAbsolutePath().toString(),
                "--id", id,
                "--output-path", output.toAbsolutePath().toString()
        );
        readProcessOutput(builder);
    }

    private JsonNode readProcessOutput(final ProcessBuilder builder) {
        try {
            final Process process = builder.start();
            try (InputStream input = process.getInputStream()) {
                if (Objects.nonNull(input)) {
                    System.out.println("Stream NOT Null");
                    return mapper.readTree(input);
                } else {
                    System.out.println("Stream Null");
                    System.out.println(input);
                    System.out.println(builder);
                    return null;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
