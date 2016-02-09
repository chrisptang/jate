package uk.ac.shef.dcs.jate.app;

import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.junit.*;
import org.xml.sax.SAXException;
import uk.ac.shef.dcs.jate.JATEException;
import uk.ac.shef.dcs.jate.JATEProperties;
import uk.ac.shef.dcs.jate.eval.ATEResultLoader;
import uk.ac.shef.dcs.jate.eval.GSLoader;
import uk.ac.shef.dcs.jate.eval.Scorer;
import uk.ac.shef.dcs.jate.model.JATEDocument;
import uk.ac.shef.dcs.jate.model.JATETerm;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Unit tests for App* of a set of ATE algorithms & GENIA based benchmarking test based on Embedded Solr
 *
 * Default Solr config is retrieved through testdata/solr-testbed
 * Default jate.properties is loaded from classpath
 * Enable PoS pattern based candidate extraction in Solr (see field type "jate_text_2_terms" for reference)
 *
 */
public class AppATEGENIATest extends BaseEmbeddedSolrTest {
    private static Logger LOG = Logger.getLogger(AppATEGENIATest.class.getName());

    public static final Path GENIA_CORPUS_ZIPPED_FILE = Paths.get(workingDir, "src", "test", "resource",
        "eval", "GENIAcorpus-files.zip");

    public static final Path GENIA_CORPUS_CONCEPT_FILE = Paths.get(workingDir, "src", "test", "resource",
            "eval", "GENIAcorpus-concept.txt");

    JATEProperties jateProperties = null;

    List<String> gsTerms;
    Map<String, String> initParams = null;


    protected void setSolrCoreName(){
        solrCoreName="geniaCore";
    }
    protected void setReindex(){ //change this to false if you want to use existing index
        reindex=false;
    }


    @Before
    public void setup() throws Exception {
        super.setup();
        jateProperties = new JATEProperties();
        jateProperties.setSolrHome(solrHome.toString());
        jateProperties.setSolrCoreName(solrCoreName);

        if(reindex) {
            try {
                indexCorpus(loadGENIACorpus());
            } catch (IOException ioe) {
                throw new JATEException("Unable to delete index data. Please clean index directory " +
                        "[testdata/solr-testbed/jate/data] manually!");
            }
        }

        gsTerms = GSLoader.loadGenia(GENIA_CORPUS_CONCEPT_FILE.toFile(), true, true);

        if (gsTerms == null) {
            throw new JATEException("GENIA CORPUS CONCEPT FILE CANNOT BE LOADED SUCCESSFULLY!");
        }
        initParams = new HashMap<>();

        initParams.put(AppParams.PREFILTER_MIN_TERM_TOTAL_FREQUENCY.getParamKey(), "2");
        initParams.put(AppParams.CUTOFF_TOP_K_PERCENT.getParamKey(), "0.99999");
        LOG.info("<<TEST BEGINS WITH pre-filter.minimum total term frequency=2>>");
    }

    protected List<JATEDocument> loadGENIACorpus() throws JATEException {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = null;
        Metadata metadata = new Metadata();

        List<JATEDocument> corpus = new ArrayList<>();

        try {
            ZipFile geniaCorpus = new ZipFile(GENIA_CORPUS_ZIPPED_FILE.toFile());
            Enumeration<? extends ZipEntry> entries = geniaCorpus.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String fileName = entry.getName();
                //skip file in MAC OS
                if(entry.isDirectory()||fileName.startsWith("__MACOSX/")||fileName.contains(".DS_Store"))
                    continue;

                InputStream stream = geniaCorpus.getInputStream(entry);
                handler = new BodyContentHandler(-1);
                try {
                    parser.parse(stream, handler, metadata);
                    String content = handler.toString();
                    JATEDocument doc = new JATEDocument(fileName);
                    doc.setContent(content);
                    corpus.add(doc);
                } catch (SAXException e) {
                    e.printStackTrace();
                } catch (TikaException e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            throw new JATEException(String.format("GENIA Corpus not found from %s", GENIA_CORPUS_ZIPPED_FILE));
        }

        return corpus;
    }

    protected void indexCorpus(List<JATEDocument> corpus) throws IOException, SolrServerException {
        int count=0;
        for (JATEDocument doc: corpus) {
            try {
                count++;
                super.addNewDoc(doc.getId(), doc.getId(), doc.getContent(), jateProperties, false);
                if (count%500==0){
                    LOG.info(String.format("%s documents indexed.",count));
                }
            } catch (IOException e) {
                e.printStackTrace();
            } catch (SolrServerException e) {
                e.printStackTrace();
            } catch (JATEException jateEx) {
                jateEx.printStackTrace();
                LOG.warn(String.format("failed to index document. Please check JATE properties " +
                                "for current setting for [%s] and [%s]", JATEProperties.PROPERTY_SOLR_FIELD_CONTENT_NGRAMS,
                        JATEProperties.PROPERTY_SOLR_FIELD_CONTENT_TERMS));
            }
        }
        server.commit();
    }

    //@Test
    public void validate_indexing() {
        ModifiableSolrParams params = new ModifiableSolrParams();
        params.set("q", "*:*");

        try {
            QueryResponse qResp = server.query(params);
            SolrDocumentList docList = qResp.getResults();

            assert (docList.getNumFound() == 2000);
        } catch (SolrServerException e) {
            e.printStackTrace();
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }
    //@Test
    public void benchmarking_appATTF() throws JATEException, IOException {
        AppATTF appATTF = new AppATTF(initParams);
        List<JATETerm> termList = appATTF.extract(server.getCoreContainer().getCore(solrCoreName), jateProperties);

        assert termList != null;
        // the results depends on specified PoS patterns
        // refer to genia.patterns in solr config for the default candidate extraction patterns
        // candidate extraction is performed at index-time
        Assert.assertEquals("Candidate size should be 10681.", 10681, termList.size());

        List<String> rankedTerms = ATEResultLoader.load(termList);
        double top50Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 50);
        assert 0.84 == top50Precision;

        double top100Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 100);
        assert 0.85 == top100Precision;

        double top500Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 500);
        assert 0.79 == top500Precision;

        double top1000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 1000);
        assert 0.77 == top1000Precision;

        double top3000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 3000);
        assert 0.68 == top3000Precision;

        double top5000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 5000);
        assert 0.7 == top5000Precision;

        double top8000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 8000);
        assert 0.66 == top8000Precision;

        double top10000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 10000);
        assert 0.63 == top10000Precision;

        double recall = Scorer.recall(gsTerms, rankedTerms);
        assert 0.18 == recall;

        LOG.info("=============ATTF GENIA Benchmarking Results==================");

        LOG.info("  top 50 Precision:" + top50Precision);
        LOG.info("  top 100 Precision:" + top100Precision);
        LOG.info("  top 500 Precision:" + top500Precision);
        LOG.info("  top 1000 Precision:" + top1000Precision);
        LOG.info("  top 3000 Precision:" + top3000Precision);
        LOG.info("  top 5000 Precision:" + top5000Precision);
        LOG.info("  top 8000 Precision:" + top8000Precision);
        LOG.info("  top 10000 Precision:" + top10000Precision);
        LOG.info("  overall recall:" + recall);
    }

    //@Test
    public void benchmarking_appChiSquare() throws IOException, JATEException {
        initParams.put(AppParams.CHISQUERE_FREQ_TERM_CUTOFF_PERCENTAGE.getParamKey(), "0.3");
        AppChiSquare appChiSquare = new AppChiSquare(initParams);
        List<JATETerm> termList = appChiSquare.extract(server.getCoreContainer().getCore(solrCoreName), jateProperties);

        assert termList != null;
        // the results depends on specified PoS patterns
        // refer to genia.patterns in solr config for the default candidate extraction patterns
        // candidate extraction is performed at index-time
        Assert.assertEquals("Candidate size should be 10681.", 10681, termList.size());
        List<String> rankedTerms = ATEResultLoader.load(termList);
        double top50Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 50);
        assert 0.96 == top50Precision;

        double top100Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 100);
        assert 0.86 == top100Precision;

        double top500Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 500);
        assert 0.7 == top500Precision;

        double top1000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 1000);
        assert 0.68 == top1000Precision;

        double top3000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 3000);
        assert 0.69 == top3000Precision;

        double top5000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 5000);
        assert 0.68 == top5000Precision;

        double top8000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 8000);
        assert 0.65 == top8000Precision;

        double top10000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 10000);
        assert 0.63 == top10000Precision;

        double recall = Scorer.recall(gsTerms, rankedTerms);
 //       assert 0.18 == recall;

        LOG.info("=============ChiSquare GENIA Benchmarking Results==================");

        LOG.info("  top 50 Precision:" + top50Precision);
        LOG.info("  top 100 Precision:" + top100Precision);
        LOG.info("  top 500 Precision:" + top500Precision);
        LOG.info("  top 1000 Precision:" + top1000Precision);
        LOG.info("  top 3000 Precision:" + top3000Precision);
        LOG.info("  top 5000 Precision:" + top5000Precision);
        LOG.info("  top 8000 Precision:" + top8000Precision);
        LOG.info("  top 10000 Precision:" + top10000Precision);
        LOG.info("  overall recall:" + recall);
    }

    //@Test
    public void benchmarking_appCValue() throws IOException, JATEException {
        AppCValue appCValue = new AppCValue(initParams);
        List<JATETerm> termList = appCValue.extract(server.getCoreContainer().getCore(solrCoreName), jateProperties);

        assert termList != null;
        // the results depends on specified PoS patterns
        // refer to genia.patterns in solr config for the default candidate extraction patterns
        // candidate extraction is performed at index-time
        Assert.assertEquals("Candidate size should be 10681.", 10681, termList.size());

        List<String> rankedTerms = ATEResultLoader.load(termList);
        double top50Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 50);
        assert 0.94 == top50Precision;

        double top100Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 100);
        assert 0.9 == top100Precision;

        double top500Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 500);
        assert 0.86 == top500Precision;

        double top1000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 1000);
        assert 0.82 == top1000Precision;

        double top3000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 3000);
        assert 0.73 == top3000Precision;

        double top5000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 5000);
        assert 0.64 == top5000Precision;

        double top8000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 8000);
        assert 0.63 == top8000Precision;

        double top10000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 10000);
        assert 0.62 == top10000Precision;

        double recall = Scorer.recall(gsTerms, rankedTerms);
        assert 0.18 == recall;

        LOG.info("=============AppCValue GENIA Benchmarking Results==================");

        LOG.info("  top 50 Precision:" + top50Precision);
        LOG.info("  top 100 Precision:" + top100Precision);
        LOG.info("  top 500 Precision:" + top500Precision);
        LOG.info("  top 1000 Precision:" + top1000Precision);
        LOG.info("  top 3000 Precision:" + top3000Precision);
        LOG.info("  top 5000 Precision:" + top5000Precision);
        LOG.info("  top 8000 Precision:" + top8000Precision);
        LOG.info("  top 10000 Precision:" + top10000Precision);
        LOG.info("  overall recall:" + recall);
    }

    //@Test
    public void benchmarking_appGlossEx() throws JATEException, IOException {
        initParams.put(AppParams.REFERENCE_FREQUENCY_FILE.getParamKey(), FREQ_GENIC_FILE.toString());
        AppGlossEx appGlossEx = new AppGlossEx(initParams);

        List<JATETerm> termList = appGlossEx.extract(server.getCoreContainer().getCore(solrCoreName), jateProperties);

        LOG.info("termList.size():"+termList.size());
        Assert.assertEquals("Candidate size should be 10681.", 10681, termList.size());

        List<String> rankedTerms = ATEResultLoader.load(termList);
        double top50Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 50);
        assert 0.9 == top50Precision;

        double top100Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 100);
        assert 0.84 == top100Precision;

        double top500Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 500);
        assert 0.7 == top500Precision;

        double top1000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 1000);
        assert 0.66 == top1000Precision;

        double top3000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 3000);
        assert 0.67 == top3000Precision;

        double top5000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 5000);
        assert 0.68 == top5000Precision;

        double top8000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 8000);
        assert 0.67 == top8000Precision;

        double top10000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 10000);
        assert 0.65 == top10000Precision;

        double recall = Scorer.recall(gsTerms, rankedTerms);
        assert 0.18 == recall;

        LOG.info("=============GlossEx GENIA Benchmarking Results==================");

        LOG.info("  top 50 Precision:" + top50Precision);
        LOG.info("  top 100 Precision:" + top100Precision);
        LOG.info("  top 500 Precision:" + top500Precision);
        LOG.info("  top 1000 Precision:" + top1000Precision);
        LOG.info("  top 3000 Precision:" + top3000Precision);
        LOG.info("  top 5000 Precision:" + top5000Precision);
        LOG.info("  top 8000 Precision:" + top8000Precision);
        LOG.info("  top 10000 Precision:" + top10000Precision);
        LOG.info("  overall recall:" + recall);
    }

    //@Test
    public void benchmarking_appRAKE() throws JATEException, IOException {
        AppRAKE appRAKE = new AppRAKE(initParams);
        List<JATETerm> termList = appRAKE.extract(server.getCoreContainer().getCore(solrCoreName), jateProperties);

        assert termList != null;
        // the results depends on specified PoS patterns
        // refer to genia.patterns in solr config for the default candidate extraction patterns
        // candidate extraction is performed at index-time
        Assert.assertEquals("Candidate size should be 10681.", 10681, termList.size());

        List<String> rankedTerms = ATEResultLoader.load(termList);

        //DebugHelper.writeList(rankedTerms, "candidates_new.txt");

        double top50Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 50);
        assert 0.86 == top50Precision;

        double top100Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 100);
        assert 0.85 == top100Precision;

        double top500Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 500);
        assert 0.64 == top500Precision;

        double top1000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 1000);
        assert 0.61 == top1000Precision;

        double top3000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 3000);
        assert 0.63 == top3000Precision;

        double top5000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 5000);
        assert 0.62 == top5000Precision;

        double top8000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 8000);
        assert 0.61 == top8000Precision;

        double top10000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 10000);
        assert 0.63 == top10000Precision;

        double recall = Scorer.recall(gsTerms, rankedTerms);
        assert 0.18 == recall;

        LOG.info("=============AppRAKE GENIA Benchmarking Results==================");

        LOG.info("  top 50 Precision:" + top50Precision);
        LOG.info("  top 100 Precision:" + top100Precision);
        LOG.info("  top 500 Precision:" + top500Precision);
        LOG.info("  top 1000 Precision:" + top1000Precision);
        LOG.info("  top 3000 Precision:" + top3000Precision);
        LOG.info("  top 5000 Precision:" + top5000Precision);
        LOG.info("  top 8000 Precision:" + top8000Precision);
        LOG.info("  top 10000 Precision:" + top10000Precision);
        LOG.info("  overall recall:" + recall);
    }

    //@Test
    public void benchmarking_appRIDF() throws JATEException, IOException {
        AppRIDF appRIDF = new AppRIDF(initParams);
        List<JATETerm> termList = appRIDF.extract(server.getCoreContainer().getCore(solrCoreName), jateProperties);

        assert termList != null;
        // the results depends on specified PoS patterns
        // refer to genia.patterns in solr config for the default candidate extraction patterns
        // candidate extraction is performed at index-time
        Assert.assertEquals("Candidate size should be 10681.", 10681, termList.size());

        List<String> rankedTerms = ATEResultLoader.load(termList);
        double top50Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 50);
        assert 0.92 == top50Precision;

        double top100Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 100);
        assert 0.93 == top100Precision;

        double top500Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 500);
        assert 0.87 == top500Precision;

        double top1000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 1000);
        assert 0.83 == top1000Precision;

        double top3000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 3000);
        assert 0.8 == top3000Precision;

        double top5000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 5000);
        assert 0.71 == top5000Precision;

        double top8000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 8000);
        assert 0.67 == top8000Precision;

        double top10000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 10000);
        assert 0.63 == top10000Precision;

        double recall = Scorer.recall(gsTerms, rankedTerms);
        assert 0.18 == recall;

        LOG.info("=============AppRIDF GENIA Benchmarking Results==================");

        LOG.info("  top 50 Precision:" + top50Precision);
        LOG.info("  top 100 Precision:" + top100Precision);
        LOG.info("  top 500 Precision:" + top500Precision);
        LOG.info("  top 1000 Precision:" + top1000Precision);
        LOG.info("  top 3000 Precision:" + top3000Precision);
        LOG.info("  top 5000 Precision:" + top5000Precision);
        LOG.info("  top 8000 Precision:" + top8000Precision);
        LOG.info("  top 10000 Precision:" + top10000Precision);
        LOG.info("  overall recall:" + recall);
    }

    @Test
    public void benchmarking_appTermEx() throws JATEException, IOException {
        initParams.put(AppParams.REFERENCE_FREQUENCY_FILE.getParamKey(), FREQ_GENIC_FILE.toString());
        AppTermEx appTermEx = new AppTermEx(initParams);

        List<JATETerm> termList = appTermEx.extract(server.getCoreContainer().getCore(solrCoreName), jateProperties);
        // the results depends on specified PoS patterns
        // refer to genia.patterns in solr config for the default candidate extraction patterns
        // candidate extraction is performed at index-time
        //LOG.info("termList.size():"+termList.size());
        Assert.assertEquals("Candidate size should be 10681.", 10681, termList.size());

        List<String> rankedTerms = ATEResultLoader.load(termList);
        double top50Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 50);
        assert 0.88 == top50Precision;

        double top100Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 100);
        assert 0.9 == top100Precision;

        double top500Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 500);
        assert 0.87 == top500Precision;

        double top1000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 1000);
        assert 0.85 == top1000Precision;

        double top3000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 3000);
        assert 0.84 == top3000Precision;

        double top5000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 5000);
        assert 0.82 == top5000Precision;

        double top8000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 8000);
        assert 0.76 == top8000Precision;

        double top10000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 10000);
        assert 0.66 == top10000Precision;

        double recall = Scorer.recall(gsTerms, rankedTerms);
        assert 0.18 == recall;

        LOG.info("=============AppTermEx GENIA Benchmarking Results==================");

        LOG.info("  top 50 Precision:" + top50Precision);
        LOG.info("  top 100 Precision:" + top100Precision);
        LOG.info("  top 500 Precision:" + top500Precision);
        LOG.info("  top 1000 Precision:" + top1000Precision);
        LOG.info("  top 3000 Precision:" + top3000Precision);
        LOG.info("  top 5000 Precision:" + top5000Precision);
        LOG.info("  top 8000 Precision:" + top8000Precision);
        LOG.info("  top 10000 Precision:" + top10000Precision);
        LOG.info("  overall recall:" + recall);
    }

    //@Test
    public void benchmarking_appTFIDF()throws JATEException, IOException {
        AppTFIDF appTFIDF = new AppTFIDF(initParams);

        List<JATETerm> termList = appTFIDF.extract(server.getCoreContainer().getCore(solrCoreName), jateProperties);
        //LOG.info("termList.size():"+termList.size());
        Assert.assertEquals("Candidate size should be 10681.", 10681, termList.size());

        List<String> rankedTerms = ATEResultLoader.load(termList);
        double top50Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 50);
        assert 0.96 == top50Precision;

        double top100Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 100);
        assert 0.93 == top100Precision;

        double top500Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 500);
        assert 0.84 == top500Precision;

        double top1000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 1000);
        assert 0.83 == top1000Precision;

        double top3000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 3000);
        assert 0.77 == top3000Precision;

        double top5000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 5000);
        assert 0.73 == top5000Precision;

        double top8000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 8000);
        assert 0.67 == top8000Precision;

        double top10000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 10000);
        assert 0.63 == top10000Precision;

        double recall = Scorer.recall(gsTerms, rankedTerms);
        assert 0.18 == recall;

        LOG.info("=============TFIDF GENIA Benchmarking Results==================");

        LOG.info("  top 50 Precision:" + top50Precision);
        LOG.info("  top 100 Precision:" + top100Precision);
        LOG.info("  top 500 Precision:" + top500Precision);
        LOG.info("  top 1000 Precision:" + top1000Precision);
        LOG.info("  top 3000 Precision:" + top3000Precision);
        LOG.info("  top 5000 Precision:" + top5000Precision);
        LOG.info("  top 8000 Precision:" + top8000Precision);
        LOG.info("  top 10000 Precision:" + top10000Precision);
        LOG.info("  overall recall:" + recall);
    }

    //@Test
    public void benchmarking_appTTF() throws JATEException, IOException {
        AppTTF appTTF = new AppTTF(initParams);

        List<JATETerm> termList = appTTF.extract(server.getCoreContainer().getCore(solrCoreName), jateProperties);

        // the results depends on specified PoS patterns
        // refer to genia.patterns in solr config for the default candidate extraction patterns
        // candidate extraction is performed at index-time
        LOG.info("termList.size():"+termList.size());
        Assert.assertEquals("Candidate size should be 10681.", 10681, termList.size());

        List<String> rankedTerms = ATEResultLoader.load(termList);
        double top50Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 50);
        assert 0.96 == top50Precision;

        double top100Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 100);
        assert 0.91 == top100Precision;

        double top500Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 500);
        assert 0.83 == top500Precision;

        double top1000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 1000);
        assert 0.82 == top1000Precision;

        double top3000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 3000);
        assert 0.77 == top3000Precision;

        double top5000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 5000);
        assert 0.72 == top5000Precision;

        double top8000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 8000);
        assert 0.65 == top8000Precision;

        double top10000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 10000);
        assert 0.63 == top10000Precision;

        double recall = Scorer.recall(gsTerms, rankedTerms);
        assert 0.18 == recall;

        LOG.info("=============TTF GENIA Benchmarking Results==================");

        LOG.info("  top 50 Precision:" + top50Precision);
        LOG.info("  top 100 Precision:" + top100Precision);
        LOG.info("  top 500 Precision:" + top500Precision);
        LOG.info("  top 1000 Precision:" + top1000Precision);
        LOG.info("  top 3000 Precision:" + top3000Precision);
        LOG.info("  top 5000 Precision:" + top5000Precision);
        LOG.info("  top 8000 Precision:" + top8000Precision);
        LOG.info("  top 10000 Precision:" + top10000Precision);
        LOG.info("  overall recall:" + recall);
    }

    //@Test
    public void benchmarking_appWeirdness()throws JATEException, IOException {
        initParams.put(AppParams.REFERENCE_FREQUENCY_FILE.getParamKey(), FREQ_GENIC_FILE.toString());
        AppWeirdness appWeirdness = new AppWeirdness(initParams);

        List<JATETerm> termList = appWeirdness.extract(server.getCoreContainer().getCore(solrCoreName), jateProperties);
        // the results depends on specified PoS patterns
        // refer to genia.patterns in solr config for the default candidate extraction patterns
        // candidate extraction is performed at index-time
        LOG.info("termList.size():"+termList.size());
        Assert.assertEquals("Candidate size should be 10681.", 10681, termList.size());

        List<String> rankedTerms = ATEResultLoader.load(termList);
        double top50Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 50);
        assert 0.76 == top50Precision;

        double top100Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 100);
        assert 0.82 == top100Precision;

        double top500Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 500);
        assert 0.87 == top500Precision;

        double top1000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 1000);
        assert 0.86 == top1000Precision;

        double top3000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 3000);
        assert 0.8 == top3000Precision;

        double top5000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 5000);
        assert 0.75 == top5000Precision;

        double top8000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 8000);
        assert 0.68 == top8000Precision;

        double top10000Precision = Scorer.computePrecisionWithNormalisation(gsTerms, rankedTerms, true, false, true, 10000);
        assert 0.64 == top10000Precision;

        double recall = Scorer.recall(gsTerms, rankedTerms);
        assert 0.18 == recall;

        LOG.info("=============appWeirdness GENIA Benchmarking Results==================");

        LOG.info("  top 50 Precision:" + top50Precision);
        LOG.info("  top 100 Precision:" + top100Precision);
        LOG.info("  top 500 Precision:" + top500Precision);
        LOG.info("  top 1000 Precision:" + top1000Precision);
        LOG.info("  top 3000 Precision:" + top3000Precision);
        LOG.info("  top 5000 Precision:" + top5000Precision);
        LOG.info("  top 8000 Precision:" + top8000Precision);
        LOG.info("  top 10000 Precision:" + top10000Precision);
        LOG.info("  overall recall:" + recall);
    }

    @After
    public void tearDown() throws IOException {
        super.tearDown();
    }
}