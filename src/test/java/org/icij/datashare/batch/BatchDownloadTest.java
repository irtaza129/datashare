package org.icij.datashare.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.local;

public class BatchDownloadTest {
    @Test(expected = IllegalArgumentException.class)
    public void test_constructor_null_user() {
        new BatchDownload(project("prj"),null, "query");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_constructor_null_query() {
        new BatchDownload(project("prj"), local(), null);
    }

    @Test
    public void test_batch_download_constructor() {
        DatashareTime.getInstance().setMockDate("2021-07-07T14:53:47Z");

        BatchDownload batchDownload = new BatchDownload(project("prj"), local(), "foo");

        assertThat(batchDownload.project).isEqualTo(project("prj"));
        assertThat(batchDownload.query).isEqualTo("foo");
        assertThat(batchDownload.filename.toString()).isEqualTo("/tmp/archive_local_2021-07-07T14:53:47Z[GMT].zip");
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_constructor_with_json() {
        assertThat(new BatchDownload(project("prj"), User.local(), "{test}").isJsonQuery()).isTrue();
    }

    @Test
    public void test_batch_download_constructor_with_downloadDir() {
        DatashareTime.getInstance().setMockDate("2021-07-07T14:15:16Z");

        assertThat(new BatchDownload(project("prj"), local(), "foo", Paths.get("/bar"), false).filename.toString()).
                isEqualTo("/bar/archive_local_2021-07-07T14:15:16Z[GMT].zip");
    }

    @Test
    public void test_is_json() {
        assertThat(new BatchDownload(project("prj"), User.local(), "test").isJsonQuery()).isFalse();
        assertThat(new BatchDownload(project("prj"), User.local(), "{\"test\": 42}").isJsonQuery()).isTrue();
    }

    @Test
    public void test_as_json() {
        String query = "{\"foo\":\"bar\"}";
        BatchDownload batchDownload = new BatchDownload(project("prj"), local(), query);
        assertThat(batchDownload.queryAsJson().toString()).isEqualTo(query);
    }

    @Test(expected = IllegalStateException.class)
    public void test_as_json_with_not_string_query() {
        BatchDownload batchDownload = new BatchDownload(project("prj"), local(), "query");
        batchDownload.queryAsJson();
    }

    @Test
    public void test_serialize_deserialize() throws Exception {
        DatashareTime.getInstance().setMockDate("2021-07-07T14:53:47Z");
        ObjectMapper objectMapper = new ObjectMapper();

        String json = objectMapper.writeValueAsString(new BatchDownload(project("prj"), local(), "query"));
        assertThat(json).contains("\"filename\":\"file:///tmp/archive_local_2021-07-07T14:53:47Z%5BGMT%5D.zip\"");

        assertThat(objectMapper.readValue(json, BatchDownload.class)).isNotNull();
    }

    @Before public void setUp() { DatashareTime.setMockTime(true); }
    @After public void tearDown() { DatashareTime.setMockTime(false); }}