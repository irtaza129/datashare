package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.Refresh;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.NLP_PIPELINE_OPT;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.text.DocumentBuilder.createDoc;

public class EnqueueFromIndexTaskTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private final ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True);

    @After public void tearDown() throws IOException { es.removeAll();}

    @Test
    public void test_size_of_search() throws Exception {
        for (int i = 0; i < 20; i++) {
            indexer.add(TEST_INDEX, createDoc("doc" + i).with(Pipeline.Type.CORENLP).build());
        }
        Map<String, Object> properties = Map.of(
                "defaultProject", "test-datashare",
                "stages", "ENQUEUEIDX",
                "queueName", "test:queue",
                NLP_PIPELINE_OPT, Pipeline.Type.OPENNLP.name());
        MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();
        EnqueueFromIndexTask resumeNlpTask = new EnqueueFromIndexTask(factory, indexer, new Task<>(EnqueueFromIndexTask.class.getName(), new User("test"), properties), null);
        resumeNlpTask.call();
        assertThat(factory.queues.get("test:queue:nlp")).hasSize(21); // with poison
    }
}
