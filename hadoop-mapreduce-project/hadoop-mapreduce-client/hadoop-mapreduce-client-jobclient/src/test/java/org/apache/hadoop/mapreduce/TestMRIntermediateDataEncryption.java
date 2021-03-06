/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.mapreduce;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.MiniMRClientCluster;
import org.apache.hadoop.mapred.MiniMRClientClusterFactory;
import org.apache.hadoop.mapred.Utils;

import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.reduce.LongSumReducer;
import org.apache.hadoop.mapreduce.security.IntermediateEncryptedStream;
import org.apache.hadoop.mapreduce.security.SpillCallBackPathsFinder;
import org.apache.hadoop.mapreduce.util.MRJobConfUtil;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.util.Time;
import org.apache.hadoop.util.ToolRunner;

/**
 * This class tests the support of Intermediate data encryption
 * (Spill data encryption).
 * It starts by generating random input text file ({@link RandomTextWriter})
 * using the {@link ToolRunner}.
 * A wordCount job consumes the generated input. The final job is configured in
 * a way to guarantee that data is spilled.
 * mbs-per-map specifies the amount of data (in MBs) to generate per map.
 * By default, this is twice the value of <code>mapreduce.task.io.sort.mb</code>
 * <code>map-tasks</code> specifies the number of map tasks to run.
 */
@RunWith(Parameterized.class)
public class TestMRIntermediateDataEncryption {
  /**
   * The number of bytes generated by the input generator.
   */
  public static final long TOTAL_MBS_DEFAULT = 128L;
  public static final long BLOCK_SIZE_DEFAULT = 32 * 1024 * 1024L;
  public static final int INPUT_GEN_NUM_THREADS = 16;
  public static final long TASK_SORT_IO_MB_DEFAULT = 128L;
  public static final String JOB_DIR_PATH = "jobs-data-path";
  private static final Logger LOG =
      LoggerFactory.getLogger(TestMRIntermediateDataEncryption.class);
  /**
   * Directory of the test data.
   */
  private static File testRootDir;
  private static volatile BufferedWriter inputBufferedWriter;
  private static Configuration commonConfig;
  private static MiniDFSCluster dfsCluster;
  private static MiniMRClientCluster mrCluster;
  private static FileSystem fs;
  private static Path jobInputDirPath;
  private static long inputFileSize;
  /**
   * Test parameters.
   */
  private final String testTitleName;
  private final int numMappers;
  private final int numReducers;
  private final boolean isUber;
  private Configuration config;
  private Path jobOutputPath;

  /**
   * Initialized the parametrized JUnit test.
   * @param testName the name of the unit test to be executed.
   * @param mappers number of mappers in the tests.
   * @param reducers number of the reducers.
   * @param uberEnabled boolean flag for isUber
   */
  public TestMRIntermediateDataEncryption(String testName, int mappers,
      int reducers, boolean uberEnabled) {
    this.testTitleName = testName;
    this.numMappers = mappers;
    this.numReducers = reducers;
    this.isUber = uberEnabled;
  }

  /**
   * List of arguments to run the JunitTest.
   * @return
   */
  @Parameterized.Parameters(
      name = "{index}: TestMRIntermediateDataEncryption.{0} .. "
          + "mappers:{1}, reducers:{2}, isUber:{3})")
  public static Collection<Object[]> getTestParameters() {
    return Arrays.asList(new Object[][]{
        {"testSingleReducer", 3, 1, false},
        {"testUberMode", 3, 1, true},
        {"testMultipleMapsPerNode", 8, 1, false},
        // TODO: The following configuration is commented out until
        //       MAPREDUCE-7325 is fixed.
        //       Setting multiple reducers breaks LocalJobRunner causing the
        //       unit test to fail.
        // {"testMultipleReducers", 2, 4, false}
    });
  }

  @BeforeClass
  public static void setupClass() throws Exception {
    // setup the test root directory
    testRootDir =
        GenericTestUtils.setupTestRootDir(
            TestMRIntermediateDataEncryption.class);
    // setup the base configurations and the clusters
    final File dfsFolder = new File(testRootDir, "dfs");
    final Path jobsDirPath = new Path(JOB_DIR_PATH);

    commonConfig = createBaseConfiguration();
    dfsCluster =
        new MiniDFSCluster.Builder(commonConfig, dfsFolder)
            .numDataNodes(2).build();
    dfsCluster.waitActive();
    mrCluster = MiniMRClientClusterFactory.create(
        TestMRIntermediateDataEncryption.class, 2, commonConfig);
    mrCluster.start();
    fs = dfsCluster.getFileSystem();
    if (fs.exists(jobsDirPath) && !fs.delete(jobsDirPath, true)) {
      throw new IOException("Could not delete JobsDirPath" + jobsDirPath);
    }
    fs.mkdirs(jobsDirPath);
    jobInputDirPath = new Path(jobsDirPath, "in-dir");
    // run the input generator job.
    Assert.assertEquals("Generating input should succeed", 0,
        generateInputTextFile());
  }

  @AfterClass
  public static void tearDown() throws IOException {
    // shutdown clusters
    if (mrCluster != null) {
      mrCluster.stop();
    }
    if (dfsCluster != null) {
      dfsCluster.shutdown();
    }
    // make sure that generated input file is deleted
    final File textInputFile = new File(testRootDir, "input.txt");
    if (textInputFile.exists()) {
      textInputFile.delete();
    }
  }

  /**
   * Creates a configuration object setting the common properties before
   * initializing the clusters.
   * @return configuration to be used as a base for the unit tests.
   */
  private static Configuration createBaseConfiguration() {
    // Set the jvm arguments to enable intermediate encryption.
    Configuration conf =
        MRJobConfUtil.initEncryptedIntermediateConfigsForTesting(null);
    // Set the temp directories a subdir of the test directory.
    conf = MRJobConfUtil.setLocalDirectoriesConfigForTesting(conf, testRootDir);
    conf.setLong("dfs.blocksize", BLOCK_SIZE_DEFAULT);
    return conf;
  }

  /**
   * Creates a thread safe BufferedWriter to be used among the task generators.
   * @return A synchronized <code>BufferedWriter</code> to the input file.
   * @throws IOException
   */
  private static synchronized BufferedWriter getTextInputWriter()
      throws IOException {
    if (inputBufferedWriter == null) {
      final File textInputFile = new File(testRootDir, "input.txt");
      inputBufferedWriter = new BufferedWriter(new FileWriter(textInputFile));
    }
    return inputBufferedWriter;
  }

  /**
   * Generates input text file of size <code>TOTAL_MBS_DEFAULT</code>.
   * It creates a total <code>INPUT_GEN_NUM_THREADS</code> future tasks.
   *
   * @return the result of the input generation. 0 for success.
   * @throws Exception
   */
  private static int generateInputTextFile() throws Exception {
    final File textInputFile = new File(testRootDir, "input.txt");
    final AtomicLong actualWrittenBytes = new AtomicLong(0);
    // create INPUT_GEN_NUM_THREADS callables
    final ExecutorService executor =
        Executors.newFixedThreadPool(INPUT_GEN_NUM_THREADS);
    //create a list to hold the Future object associated with Callable
    final List<Future<Long>> inputGenerators = new ArrayList<>();
    final Callable<Long> callableGen = new InputGeneratorTask();
    final long startTime = Time.monotonicNow();
    for (int i = 0; i < INPUT_GEN_NUM_THREADS; i++) {
      //submit Callable tasks to be executed by thread pool
      Future<Long> genFutureTask = executor.submit(callableGen);
      inputGenerators.add(genFutureTask);
    }
    for (Future<Long> genFutureTask : inputGenerators) {
      // print the return value of Future, notice the output delay in console
      // because Future.get() waits for task to get completed
      LOG.info("Received one task. Current total bytes: {}",
          actualWrittenBytes.addAndGet(genFutureTask.get()));
    }
    getTextInputWriter().close();
    final long endTime = Time.monotonicNow();
    LOG.info("Finished generating input. Wrote {} bytes in {} seconds",
        actualWrittenBytes.get(), ((endTime - startTime) * 1.0) / 1000);
    executor.shutdown();
    // copy text file to HDFS deleting the source.
    fs.mkdirs(jobInputDirPath);
    Path textInputPath =
        fs.makeQualified(new Path(jobInputDirPath, "input.txt"));
    fs.copyFromLocalFile(true, new Path(textInputFile.getAbsolutePath()),
        textInputPath);
    if (!fs.exists(textInputPath)) {
      // the file was not generated. Fail.
      return 1;
    }
    // update the input size.
    FileStatus[] fileStatus =
        fs.listStatus(textInputPath);
    inputFileSize = fileStatus[0].getLen();
    LOG.info("Text input file; path: {}, size: {}",
        textInputPath, inputFileSize);
    return 0;
  }

  @Before
  public void setup() throws Exception {
    LOG.info("Starting TestMRIntermediateDataEncryption#{}.......",
        testTitleName);
    final Path jobDirPath = new Path(JOB_DIR_PATH, testTitleName);
    if (fs.exists(jobDirPath) && !fs.delete(jobDirPath, true)) {
      throw new IOException("Could not delete " + jobDirPath);
    }
    fs.mkdirs(jobDirPath);
    jobOutputPath = new Path(jobDirPath, "out-dir");
    // Set the configuration for the job.
    config = new Configuration(commonConfig);
    config.setBoolean(MRJobConfig.JOB_UBERTASK_ENABLE, isUber);
    config.setFloat(MRJobConfig.COMPLETED_MAPS_FOR_REDUCE_SLOWSTART, 1.0F);
    // set the configuration to make sure that we get spilled files
    long ioSortMb = TASK_SORT_IO_MB_DEFAULT;
    config.setLong(MRJobConfig.IO_SORT_MB, ioSortMb);
    long mapMb = Math.max(2 * ioSortMb, config.getInt(MRJobConfig.MAP_MEMORY_MB,
        MRJobConfig.DEFAULT_MAP_MEMORY_MB));
    // make sure the map tasks will spill to disk.
    config.setLong(MRJobConfig.MAP_MEMORY_MB, mapMb);
    config.set(MRJobConfig.MAP_JAVA_OPTS, "-Xmx" + (mapMb - 200) + "m");
    config.setInt(MRJobConfig.NUM_MAPS, numMappers);
    // max attempts have to be set to 1 when intermediate encryption is enabled.
    config.setInt("mapreduce.map.maxattempts", 1);
    config.setInt("mapreduce.reduce.maxattempts", 1);
  }

  @Test
  public void testWordCount() throws Exception {
    LOG.info("........Starting main Job Driver #{} starting at {}.......",
        testTitleName, Time.formatTime(System.currentTimeMillis()));
    Job job = Job.getInstance(config);
    job.getConfiguration().setInt(MRJobConfig.NUM_MAPS, numMappers);
    job.setJarByClass(TestMRIntermediateDataEncryption.class);
    job.setJobName("mr-spill-" + testTitleName);
    // Mapper configuration
    job.setMapperClass(TokenizerMapper.class);
    job.setInputFormatClass(TextInputFormat.class);
    job.setCombinerClass(LongSumReducer.class);
    FileInputFormat.setMinInputSplitSize(job,
        (inputFileSize + numMappers) / numMappers);
    // Reducer configuration
    job.setReducerClass(LongSumReducer.class);
    job.setNumReduceTasks(numReducers);
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(LongWritable.class);
    // Set the IO paths for the job.
    FileInputFormat.addInputPath(job, jobInputDirPath);
    FileOutputFormat.setOutputPath(job, jobOutputPath);
    SpillCallBackPathsFinder spillInjector =
        (SpillCallBackPathsFinder) IntermediateEncryptedStream
            .setSpillCBInjector(new SpillCallBackPathsFinder());
    StringBuilder testSummary =
        new StringBuilder(String.format("%n ===== test %s summary ======",
            testTitleName));
    try {
      long startTime = Time.monotonicNow();
      testSummary.append(String.format("%nJob %s ended at %s",
          testTitleName, Time.formatTime(System.currentTimeMillis())));
      Assert.assertTrue(job.waitForCompletion(true));
      long endTime = Time.monotonicNow();
      testSummary.append(String.format("%nJob %s ended at %s",
              job.getJobName(), Time.formatTime(System.currentTimeMillis())));
      testSummary.append(String.format("%n\tThe job took %.3f seconds",
          (1.0 * (endTime - startTime)) / 1000));
      long spilledRecords =
          job.getCounters().findCounter(TaskCounter.SPILLED_RECORDS).getValue();
      Assert.assertFalse(
          "The encrypted spilled files should not be empty.",
          spillInjector.getEncryptedSpilledFiles().isEmpty());
      Assert.assertTrue("Spill records must be greater than 0",
          spilledRecords > 0);
      Assert.assertTrue("Job Output path [" + jobOutputPath + "] should exist",
          fs.exists(jobOutputPath));
      Assert.assertTrue("Invalid access to spill file positions",
          spillInjector.getInvalidSpillEntries().isEmpty());
      FileStatus[] fileStatus =
          fs.listStatus(jobOutputPath,
              new Utils.OutputFileUtils.OutputFilesFilter());
      for (FileStatus fStatus : fileStatus) {
        long fileSize = fStatus.getLen();
        testSummary.append(
            String.format("%n\tOutput file %s: %d",
                fStatus.getPath(), fileSize));
      }
    } finally {
      testSummary.append(spillInjector.getSpilledFileReport());
      LOG.info(testSummary.toString());
      IntermediateEncryptedStream.resetSpillCBInjector();
    }
  }

  /**
   * A callable implementation that generates a portion of the
   * <code>TOTAL_MBS_DEFAULT</code> into {@link #inputBufferedWriter}.
   */
  static class InputGeneratorTask implements Callable<Long> {
    @Override
    public Long call() throws Exception {
      long bytesWritten = 0;
      final ThreadLocalRandom rand = ThreadLocalRandom.current();
      final long totalBytes = 1024 * 1024 * TOTAL_MBS_DEFAULT;
      final long bytesPerTask = totalBytes / INPUT_GEN_NUM_THREADS;
      final String newLine = System.lineSeparator();
      final BufferedWriter writer = getTextInputWriter();
      while (bytesWritten < bytesPerTask) {
        String sentence =
            RandomTextWriter.generateSentenceWithRand(rand, rand.nextInt(5, 20))
                .concat(newLine);
        writer.write(sentence);
        bytesWritten += sentence.length();
      }
      writer.flush();
      LOG.info("Task {} finished. Wrote {} bytes.",
          Thread.currentThread().getName(), bytesWritten);
      return bytesWritten;
    }
  }

  /**
   * A Test tokenizer Mapper.
   */
  public static class TokenizerMapper
      extends Mapper<Object, Text, Text, LongWritable> {

    private final static LongWritable ONE = new LongWritable(1);
    private final Text word = new Text();

    public void map(Object key, Text value,
        Context context) throws IOException, InterruptedException {
      StringTokenizer itr = new StringTokenizer(value.toString());
      while (itr.hasMoreTokens()) {
        word.set(itr.nextToken());
        context.write(word, ONE);
      }
    }
  }
}
