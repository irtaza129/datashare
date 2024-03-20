package org.icij.datashare.web;

import net.codestory.http.routes.Routes;
import net.codestory.rest.Response;
import net.codestory.rest.RestAssert;
import net.codestory.rest.ShouldChain;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.db.JooqRepository;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.nlp.EmailPipeline;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.tasks.*;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.AbstractModels;
import org.icij.datashare.user.User;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.function.BiFunction;

import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.cli.DatashareCliOptions.DATA_DIR_OPT;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class TaskResourceTest extends AbstractProdWebServerTest {
    @Rule public DatashareTimeRule time = new DatashareTimeRule("2021-07-07T12:23:34Z");
    @Mock JooqRepository jooqRepository;
    private static final TaskFactoryForTest taskFactory = mock(TaskFactoryForTest.class);
    private static final BlockingQueue<TaskView<?>> taskQueue = new ArrayBlockingQueue<>(3);
    private static final TaskManagerMemory taskManager= new TaskManagerMemory(taskQueue, taskFactory);

    @Before
    public void setUp() {
        initMocks(this);
        when(jooqRepository.getProjects()).thenReturn(new ArrayList<>());
        final PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<>() {{
            put("mode", "LOCAL");
        }});
        PipelineRegistry pipelineRegistry = new PipelineRegistry(propertiesProvider);
        pipelineRegistry.register(EmailPipeline.class);
        LocalUserFilter localUserFilter = new LocalUserFilter(propertiesProvider, jooqRepository);
        configure(new CommonMode(propertiesProvider.getProperties()) {
                    @Override
                    protected void configure() {
                        bind(TaskFactory.class).toInstance(taskFactory);
                        bind(Indexer.class).toInstance(mock(Indexer.class));
                        bind(TaskManager.class).toInstance(taskManager);
                        bind(TaskSupplier.class).toInstance(taskManager);
                        bind(TaskModifier.class).toInstance(taskManager);
                        bind(PipelineRegistry.class).toInstance(pipelineRegistry);
                        bind(LocalUserFilter.class).toInstance(localUserFilter);
                        bind(PropertiesProvider.class).toInstance(new PropertiesProvider(getDefaultProperties()));
                    }
            @Override protected Routes addModeConfiguration(Routes routes) {
                        return routes.add(TaskResource.class).filter(LocalUserFilter.class);}
                }.createWebConfiguration());
        init(taskFactory);
    }

    @After
    public void tearDown() {
        taskManager.clear();
    }

    @Test
    public void test_index_file() {
        RestAssert response = post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), "{}");

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());
        responseBody.should().contain(format("{\"id\":\"%s\"", taskNames.get(0)));
        responseBody.should().contain(format("{\"id\":\"%s\"", taskNames.get(1)));
    }

    @Test
    public void test_index_file_and_filter() {
        String body ="{\"options\":{\"filter\": true}}";
        RestAssert response = post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), body);

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());
        responseBody.should().contain(format("{\"id\":\"%s\"", taskNames.get(0)));
        responseBody.should().contain(format("{\"id\":\"%s\"", taskNames.get(1)));

        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask").get().properties).includes(entry("reportName", "extract:report"));
    }

    @Test
    public void test_index_file_and_filter_with_custom_report_map() {
        String body = "{\"options\":{\"filter\": true, \"reportName\": \"extract:report:foo\"}}";
        RestAssert response = post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), body);

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());
        responseBody.should().contain(format("{\"id\":\"%s\"", taskNames.get(0)));
        responseBody.should().contain(format("{\"id\":\"%s\"", taskNames.get(1)));

        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask").get().properties).includes(entry("reportName", "extract:report:foo"));
    }

    @Test
    public void test_index_file_and_filter_with_custom_queue() {
        String body = "{\"options\":{\"filter\": true, \"queueName\": \"extract:queue:foo\"}}";
        RestAssert response = post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), body);

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());
        responseBody.should().contain(format("{\"id\":\"%s\"", taskNames.get(0)));
        responseBody.should().contain(format("{\"id\":\"%s\"", taskNames.get(1)));
    }

    @Test
    public void test_index_directory() {
        RestAssert response = post("/api/task/batchUpdate/index/file/" + getClass().getResource("/docs/").getPath().substring(1), "{}");

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());
        responseBody.should().contain(format("{\"id\":\"%s\"", taskNames.get(0)));
        responseBody.should().contain(format("{\"id\":\"%s\"", taskNames.get(1)));
    }

    @Test(timeout = 2000)
    public void test_index_and_scan_default_directory() {
        RestAssert response = post("/api/task/batchUpdate/index/file", "{}");
        HashMap<String, Object> properties = getDefaultProperties();
        properties.put("foo", "bar");

        response.should().respond(200).haveType("application/json");
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ScanTask").get().properties).
                includes(entry("dataDir", "/default/data/dir"));
    }

    @Test
    public void test_index_and_scan_directory_with_options() {
        String path = getClass().getResource("/docs").getPath();

        RestAssert response = post("/api/task/batchUpdate/index/" + path.substring(1),
                "{\"options\":{\"foo\":\"baz\",\"key\":\"val\"}}");

        response.should().haveType("application/json");
        HashMap<String, Object> defaultProperties = getDefaultProperties();
        defaultProperties.put("foo", "baz");
        defaultProperties.put("key", "val");
        assertThat(taskManager.getTasks()).hasSize(2);
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask").get().properties).isEqualTo(defaultProperties);
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ScanTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ScanTask").get().properties.get(DATA_DIR_OPT)).isEqualTo(path);
    }

    @Test
    public void test_index_queue_with_options() {
        RestAssert response = post("/api/task/batchUpdate/index", "{\"options\":{\"key1\":\"val1\",\"key2\":\"val2\"}}");

        response.should().haveType("application/json");
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask").get().properties).isEqualTo((new HashMap<>() {{
            put("key1", "val1");
            put("key2", "val2");
        }}));
        assertThat(taskManager.getTasks()).hasSize(1);
    }

    @Test
    public void test_scan_with_options() {
        String path = getClass().getResource("/docs").getPath();
        RestAssert response = post("/api/task/batchUpdate/scan/" + path.substring(1),
                "{\"options\":{\"key\":\"val\",\"foo\":\"qux\"}}");

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());
        assertThat(taskNames.size()).isEqualTo(1);
        responseBody.should().contain(format("{\"id\":\"%s\"", taskNames.get(0)));
        HashMap<String, Object> defaultProperties = getDefaultProperties();
        defaultProperties.put("key", "val");
        defaultProperties.put("foo", "qux");
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ScanTask").get().properties).
                includes(entry("key", "val"), entry("foo", "qux"), entry(DATA_DIR_OPT, path));
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.IndexTask")).isNotNull();
    }

    @Test
    public void test_findNames_should_create_resume() {
        RestAssert response = post("/api/task/findNames/EMAIL", "{\"options\":{\"waitForNlpApp\": false}}");

        response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());
        assertThat(taskNames.size()).isEqualTo(2);

        assertThat(findTask(taskManager, "org.icij.datashare.tasks.EnqueueFromIndexTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ExtractNlpTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ExtractNlpTask").get().properties).includes(entry("nlpPipeline", "EMAIL"));
    }

    @Test
    public void test_findNames_with_options_should_merge_with_property_provider() {
        RestAssert response = post("/api/task/findNames/EMAIL", "{\"options\":{\"waitForNlpApp\": false, \"key\":\"val\",\"foo\":\"loo\"}}");
        response.should().haveType("application/json");

        assertThat(findTask(taskManager, "org.icij.datashare.tasks.EnqueueFromIndexTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ExtractNlpTask")).isNotNull();
        assertThat(findTask(taskManager, "org.icij.datashare.tasks.ExtractNlpTask").get().properties).
                includes(
                        entry("nlpPipeline", "EMAIL"),
                        entry("key", "val"),
                        entry("foo", "loo"));
    }

    @Test
    public void test_findNames_with_resume_false_should_not_launch_resume_task() {
        RestAssert response = post("/api/task/findNames/EMAIL", "{\"options\":{\"resume\":\"false\", \"waitForNlpApp\": false}}");
        response.should().haveType("application/json");

        verify(taskFactory, never()).createEnqueueFromIndexTask(eq(null), any());
    }

    @Test
    public void test_findNames_with_sync_models_false() {
        AbstractModels.syncModels(true);
        RestAssert response = post("/api/task/findNames/EMAIL", "{\"options\":{\"syncModels\":\"false\", \"waitForNlpApp\": false}}");
        response.should().haveType("application/json");

        assertThat(AbstractModels.isSync()).isFalse();
    }

    @Test
    public void test_batch_download() throws Exception {
        Response response = post("/api/task/batchDownload", "{\"options\":{ \"projectIds\":[\"test-datashare\"], \"query\": \"*\" }}").response();

        assertThat(response.contentType()).startsWith("application/json");
        TaskView<?> task = MAPPER.readValue(response.content(), TaskView.class);
        assertThat(taskQueue.contains(task));
    }

    @Test
    public void test_batch_download_multiple_projects() throws Exception {
        Response response = post("/api/task/batchDownload", "{\"options\":{ \"projectIds\":[\"project1\", \"project2\"], \"query\": \"*\" }}").response();

        assertThat(response.contentType()).startsWith("application/json");
        TaskView<?> task = MAPPER.readValue(response.content(), TaskView.class);
        assertThat(taskQueue.contains(task));
    }

    @Test
    public void test_batch_download_uri() throws Exception  {
        Response response = post("/api/task/batchDownload", "{\"options\":{ \"projectIds\":[\"test-datashare\"], \"query\": \"*\", \"uri\": \"/an%20url-encoded%20uri\" }}").response();

        assertThat(response.contentType()).startsWith("application/json");
        TaskView<?> task = MAPPER.readValue(response.content(), TaskView.class);
        assertThat(taskQueue.contains(task));
    }


    @Test
    public void test_batch_download_json_query()  throws Exception {
        Response response = post("/api/task/batchDownload", "{\"options\":{ \"projectIds\":[\"test-datashare\"], \"query\": {\"match_all\":{}} }}").response();

        assertThat(response.contentType()).startsWith("application/json");
        TaskView<?> task = MAPPER.readValue(response.content(), TaskView.class);
        assertThat(taskQueue.contains(task));
    }

    @Test
    public void test_clean_tasks() {
        post("/api/task/batchUpdate/index/file/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), "{}").response();
        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.id).collect(toList());

        ShouldChain responseBody = post("/api/task/clean", "{}").should().haveType("application/json");

        responseBody.should().contain(taskNames.get(0));
        responseBody.should().contain(taskNames.get(1));
        assertThat(taskManager.getTasks()).isEmpty();
    }

    @Test
    public void test_clean_one_done_task() {
        TaskView<String> dummyTask = taskManager.startTask(TestTask.class.getName(), User.local(), new HashMap<>());
        taskManager.waitTasksToBeDone(1, SECONDS);
        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTask(dummyTask.id).getState()).isEqualTo(TaskView.State.DONE);

        delete("/api/task/clean/" + dummyTask.id).should().respond(200);

        assertThat(taskManager.getTasks()).hasSize(0);
    }
    @Test
    public void test_cannot_clean_unknown_task() {
        delete("/api/task/clean/UNKNOWN_TASK_NAME").should().respond(404);
    }

    @Test
    public void test_clean_task_preflight() {
        TaskView<String> dummyTask = taskManager.startTask(TestTask.class.getName(), User.local(), new HashMap<>());
        taskManager.waitTasksToBeDone(1, SECONDS);
        options("/api/task/clean/" + dummyTask.id).should().respond(200);
    }

    @Test
    public void test_cannot_clean_running_task() {
        TaskView<String> dummyTask = taskManager.startTask(TestSleepingTask.class.getName(), User.local(), new HashMap<>());
        assertThat(taskManager.getTask(dummyTask.id).getState()).isEqualTo(TaskView.State.QUEUED);
        delete("/api/task/clean/" + dummyTask.id).should().respond(403);
        assertThat(taskManager.getTasks()).hasSize(1);
        // Cancel the all tasks to avoid side-effects with other tests
        put("/api/task/stopAll").should().respond(200);
    }

    @Test
    public void test_stop_task() {
        TaskView<String> dummyTask = taskManager.startTask(TestSleepingTask.class.getName(), User.local(), new HashMap<>());
        put("/api/task/stop/" + dummyTask.id).should().respond(200).contain("true");

        assertThat(taskManager.getTask(dummyTask.id).getState()).isEqualTo(TaskView.State.CANCELLED);
        get("/api/task/all").should().respond(200).contain("\"state\":\"CANCELLED\"");
    }

    @Test
    public void test_stop_unknown_task() {
        put("/api/task/stop/foobar").should().respond(404);
    }

    @Test
    public void test_stop_all() {
        TaskView<String> t1 = taskManager.startTask(TestSleepingTask.class.getName(), User.local(), new HashMap<>());
        TaskView<String> t2 = taskManager.startTask(TestSleepingTask.class.getName(), User.local(), new HashMap<>());
        put("/api/task/stopAll").should().respond(200).
                contain(t1.id + "\":true").
                contain(t2.id + "\":true");

        assertThat(taskManager.getTask(t1.id).getState()).isEqualTo(TaskView.State.CANCELLED);
        assertThat(taskManager.getTask(t2.id).getState()).isEqualTo(TaskView.State.CANCELLED);
    }

    @Test
    public void test_stop_all_filters_running_tasks() {
        TaskView<String> dummyTask = taskManager.startTask(TestTask.class.getName(), User.local(), new HashMap<>());
        taskManager.waitTasksToBeDone(1, SECONDS);

        put("/api/task/stopAll").should().respond(200).contain("{}");
    }

    @Test
    public void test_clear_done_tasks() {
        TaskView<String> dummyTask = taskManager.startTask(TestTask.class.getName(), User.local(), new HashMap<>());
        taskManager.waitTasksToBeDone(1, SECONDS);

        put("/api/task/stopAll").should().respond(200).contain("{}");

        assertThat(taskManager.clearDoneTasks()).hasSize(1);
        assertThat(taskManager.getTasks()).hasSize(0);
    }

    @NotNull
    private HashMap<String, Object> getDefaultProperties() {
        return new HashMap<>() {{
            put("dataDir", "/default/data/dir");
            put("foo", "bar");
            put("batchDownloadDir", "app/tmp");
        }};
    }

    private Optional<TaskView<?>> findTask(TaskManagerMemory taskManager, String expectedName) {
        return taskManager.getTasks().stream().filter(t -> expectedName.equals(t.name)).findFirst();
    }

    private void init(TaskFactoryForTest taskFactory) {
        reset(taskFactory);
        when(taskFactory.createIndexTask(any(), any())).thenReturn(mock(IndexTask.class));
        when(taskFactory.createScanTask(any(), any())).thenReturn(mock(ScanTask.class));
        when(taskFactory.createDeduplicateTask(any(), any())).thenReturn(mock(DeduplicateTask.class));
        when(taskFactory.createBatchDownloadRunner(any(), any())).thenReturn(mock(BatchDownloadRunner.class));
        when(taskFactory.createScanIndexTask(any(), any())).thenReturn(mock(ScanIndexTask.class));
        when(taskFactory.createEnqueueFromIndexTask(any(), any())).thenReturn(mock(EnqueueFromIndexTask.class));
        when(taskFactory.createExtractNlpTask(any(), any())).thenReturn(mock(ExtractNlpTask.class));
        when(taskFactory.createTestTask(any(), any())).thenReturn(new TestTask(10));
        when(taskFactory.createTestSleepingTask(any(), any())).thenReturn(new TestSleepingTask(10000));
    }

    public interface TaskFactoryForTest extends TaskFactory {
        TestSleepingTask createTestSleepingTask(TaskView<Integer> taskView, BiFunction<String, Integer, Void> updateCallback);
        TestTask createTestTask(TaskView<Integer> taskView, BiFunction<String, Integer, Void> updateCallback);
    }
}
