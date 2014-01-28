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
package org.apache.twill.yarn;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.base.Throwables;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import com.google.common.io.OutputSupplier;
import com.google.common.reflect.TypeToken;
import com.google.gson.GsonBuilder;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.twill.api.EventHandlerSpecification;
import org.apache.twill.api.LocalFile;
import org.apache.twill.api.RunId;
import org.apache.twill.api.RuntimeSpecification;
import org.apache.twill.api.SecureStore;
import org.apache.twill.api.TwillController;
import org.apache.twill.api.TwillPreparer;
import org.apache.twill.api.TwillSpecification;
import org.apache.twill.api.logging.LogHandler;
import org.apache.twill.filesystem.Location;
import org.apache.twill.filesystem.LocationFactory;
import org.apache.twill.internal.ApplicationBundler;
import org.apache.twill.internal.Arguments;
import org.apache.twill.internal.Configs;
import org.apache.twill.internal.Constants;
import org.apache.twill.internal.DefaultLocalFile;
import org.apache.twill.internal.DefaultRuntimeSpecification;
import org.apache.twill.internal.DefaultTwillSpecification;
import org.apache.twill.internal.EnvKeys;
import org.apache.twill.internal.LogOnlyEventHandler;
import org.apache.twill.internal.ProcessController;
import org.apache.twill.internal.ProcessLauncher;
import org.apache.twill.internal.RunIds;
import org.apache.twill.internal.appmaster.ApplicationMasterMain;
import org.apache.twill.internal.container.TwillContainerMain;
import org.apache.twill.internal.json.ArgumentsCodec;
import org.apache.twill.internal.json.LocalFileCodec;
import org.apache.twill.internal.json.TwillSpecificationAdapter;
import org.apache.twill.internal.utils.Dependencies;
import org.apache.twill.internal.utils.Paths;
import org.apache.twill.internal.yarn.YarnAppClient;
import org.apache.twill.internal.yarn.YarnApplicationReport;
import org.apache.twill.internal.yarn.YarnUtils;
import org.apache.twill.launcher.TwillLauncher;
import org.apache.twill.zookeeper.ZKClient;
import org.apache.twill.zookeeper.ZKClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Implementation for {@link TwillPreparer} to prepare and launch distributed application on Hadoop YARN.
 */
final class YarnTwillPreparer implements TwillPreparer {

  private static final Logger LOG = LoggerFactory.getLogger(YarnTwillPreparer.class);
  private static final String KAFKA_ARCHIVE = "kafka-0.7.2.tgz";

  private final YarnConfiguration yarnConfig;
  private final TwillSpecification twillSpec;
  private final YarnAppClient yarnAppClient;
  private final ZKClient zkClient;
  private final LocationFactory locationFactory;
  private final Supplier<String> jvmOpts;
  private final YarnTwillControllerFactory controllerFactory;
  private final RunId runId;

  private final List<LogHandler> logHandlers = Lists.newArrayList();
  private final List<String> arguments = Lists.newArrayList();
  private final Set<Class<?>> dependencies = Sets.newIdentityHashSet();
  private final List<URI> resources = Lists.newArrayList();
  private final List<String> classPaths = Lists.newArrayList();
  private final ListMultimap<String, String> runnableArgs = ArrayListMultimap.create();
  private final Credentials credentials;
  private final int reservedMemory;
  private String user;

  YarnTwillPreparer(YarnConfiguration yarnConfig, TwillSpecification twillSpec, YarnAppClient yarnAppClient,
                    ZKClient zkClient, LocationFactory locationFactory, Supplier<String> jvmOpts,
                    YarnTwillControllerFactory controllerFactory) {
    this.yarnConfig = yarnConfig;
    this.twillSpec = twillSpec;
    this.yarnAppClient = yarnAppClient;
    this.zkClient = ZKClients.namespace(zkClient, "/" + twillSpec.getName());
    this.locationFactory = locationFactory;
    this.jvmOpts = jvmOpts;
    this.controllerFactory = controllerFactory;
    this.runId = RunIds.generate();
    this.credentials = createCredentials();
    this.reservedMemory = yarnConfig.getInt(Configs.Keys.JAVA_RESERVED_MEMORY_MB,
                                            Configs.Defaults.JAVA_RESERVED_MEMORY_MB);
    this.user = System.getProperty("user.name");
  }

  @Override
  public TwillPreparer addLogHandler(LogHandler handler) {
    logHandlers.add(handler);
    return this;
  }

  @Override
  public TwillPreparer setUser(String user) {
    this.user = user;
    return this;
  }

  @Override
  public TwillPreparer withApplicationArguments(String... args) {
    return withApplicationArguments(ImmutableList.copyOf(args));
  }

  @Override
  public TwillPreparer withApplicationArguments(Iterable<String> args) {
    Iterables.addAll(arguments, args);
    return this;
  }

  @Override
  public TwillPreparer withArguments(String runnableName, String... args) {
    return withArguments(runnableName, ImmutableList.copyOf(args));
  }

  @Override
  public TwillPreparer withArguments(String runnableName, Iterable<String> args) {
    runnableArgs.putAll(runnableName, args);
    return this;
  }

  @Override
  public TwillPreparer withDependencies(Class<?>... classes) {
    return withDependencies(ImmutableList.copyOf(classes));
  }

  @Override
  public TwillPreparer withDependencies(Iterable<Class<?>> classes) {
    Iterables.addAll(dependencies, classes);
    return this;
  }

  @Override
  public TwillPreparer withResources(URI... resources) {
    return withResources(ImmutableList.copyOf(resources));
  }

  @Override
  public TwillPreparer withResources(Iterable<URI> resources) {
    Iterables.addAll(this.resources, resources);
    return this;
  }

  @Override
  public TwillPreparer withClassPaths(String... classPaths) {
    return withClassPaths(ImmutableList.copyOf(classPaths));
  }

  @Override
  public TwillPreparer withClassPaths(Iterable<String> classPaths) {
    Iterables.addAll(this.classPaths, classPaths);
    return this;
  }

  @Override
  public TwillPreparer addSecureStore(SecureStore secureStore) {
    Object store = secureStore.getStore();
    Preconditions.checkArgument(store instanceof Credentials, "Only Hadoop Credentials is supported.");
    this.credentials.mergeAll((Credentials) store);
    return this;
  }

  @Override
  public TwillController start() {
    try {
      final ProcessLauncher<ApplicationId> launcher = yarnAppClient.createLauncher(user, twillSpec);
      final ApplicationId appId = launcher.getContainerInfo();

      Callable<ProcessController<YarnApplicationReport>> submitTask =
        new Callable<ProcessController<YarnApplicationReport>>() {
        @Override
        public ProcessController<YarnApplicationReport> call() throws Exception {
          String fsUser = locationFactory.getHomeLocation().getName();

          // Local files needed by AM
          Map<String, LocalFile> localFiles = Maps.newHashMap();
          // Local files declared by runnables
          Multimap<String, LocalFile> runnableLocalFiles = HashMultimap.create();

          String vmOpts = jvmOpts.get();

          createAppMasterJar(createBundler(), localFiles);
          createContainerJar(createBundler(), localFiles);
          populateRunnableLocalFiles(twillSpec, runnableLocalFiles);
          saveSpecification(twillSpec, runnableLocalFiles, localFiles);
          saveLogback(localFiles);
          saveLauncher(localFiles);
          saveVmOptions(vmOpts, localFiles);
          saveArguments(new Arguments(arguments, runnableArgs), localFiles);
          saveLocalFiles(localFiles, ImmutableSet.of(Constants.Files.TWILL_SPEC,
                                                     Constants.Files.LOGBACK_TEMPLATE,
                                                     Constants.Files.CONTAINER_JAR,
                                                     Constants.Files.LAUNCHER_JAR,
                                                     Constants.Files.ARGUMENTS));

          LOG.debug("Submit AM container spec: {}", appId);
          // java -Djava.io.tmpdir=tmp -cp launcher.jar:$HADOOP_CONF_DIR -XmxMemory
          //     org.apache.twill.internal.TwillLauncher
          //     appMaster.jar
          //     org.apache.twill.internal.appmaster.ApplicationMasterMain
          //     false
          return launcher.prepareLaunch(
            ImmutableMap.<String, String>builder()
              .put(EnvKeys.TWILL_FS_USER, fsUser)
              .put(EnvKeys.TWILL_APP_DIR, getAppLocation().toURI().toASCIIString())
              .put(EnvKeys.TWILL_ZK_CONNECT, zkClient.getConnectString())
              .put(EnvKeys.TWILL_RUN_ID, runId.getId())
              .put(EnvKeys.TWILL_RESERVED_MEMORY_MB, Integer.toString(reservedMemory))
              .put(EnvKeys.TWILL_APP_NAME, twillSpec.getName()).build(),
            localFiles.values(), credentials)
            .noResources()
            .noEnvironment()
            .withCommands().add(
              "java",
              "-Djava.io.tmpdir=tmp",
              "-Dyarn.appId=$" + EnvKeys.YARN_APP_ID_STR,
              "-Dtwill.app=$" + EnvKeys.TWILL_APP_NAME,
              "-cp", Constants.Files.LAUNCHER_JAR + ":$HADOOP_CONF_DIR",
              "-Xmx" + (Constants.APP_MASTER_MEMORY_MB - Constants.APP_MASTER_RESERVED_MEMORY_MB) + "m",
              vmOpts,
              TwillLauncher.class.getName(),
              Constants.Files.APP_MASTER_JAR,
              ApplicationMasterMain.class.getName(),
              Boolean.FALSE.toString())
            .redirectOutput(Constants.STDOUT)
            .redirectError(Constants.STDERR)
            .launch();
        }
      };

      YarnTwillController controller = controllerFactory.create(runId, logHandlers, submitTask);
      controller.start();
      return controller;
    } catch (Exception e) {
      LOG.error("Failed to submit application {}", twillSpec.getName(), e);
      throw Throwables.propagate(e);
    }
  }

  private Credentials createCredentials() {
    Credentials credentials = new Credentials();

    try {
      credentials.addAll(UserGroupInformation.getCurrentUser().getCredentials());

      List<Token<?>> tokens = YarnUtils.addDelegationTokens(yarnConfig, locationFactory, credentials);
      for (Token<?> token : tokens) {
        LOG.debug("Delegation token acquired for {}, {}", locationFactory.getHomeLocation().toURI(), token);
      }
    } catch (IOException e) {
      LOG.warn("Failed to check for secure login type. Not gathering any delegation token.", e);
    }
    return credentials;
  }

  private ApplicationBundler createBundler() {
    return new ApplicationBundler(ImmutableList.<String>of());
  }

  private LocalFile createLocalFile(String name, Location location) throws IOException {
    return createLocalFile(name, location, false);
  }

  private LocalFile createLocalFile(String name, Location location, boolean archive) throws IOException {
    return new DefaultLocalFile(name, location.toURI(), location.lastModified(), location.length(), archive, null);
  }

  private void createAppMasterJar(ApplicationBundler bundler, Map<String, LocalFile> localFiles) throws IOException {
    try {
      LOG.debug("Create and copy {}", Constants.Files.APP_MASTER_JAR);
      Location location = createTempLocation(Constants.Files.APP_MASTER_JAR);

      List<Class<?>> classes = Lists.newArrayList();
      classes.add(ApplicationMasterMain.class);

      // Stuck in the yarnAppClient class to make bundler being able to pickup the right yarn-client version
      classes.add(yarnAppClient.getClass());

      // Add the TwillRunnableEventHandler class
      if (twillSpec.getEventHandler() != null) {
        classes.add(getClassLoader().loadClass(twillSpec.getEventHandler().getClassName()));
      }

      bundler.createBundle(location, classes);
      LOG.debug("Done {}", Constants.Files.APP_MASTER_JAR);

      localFiles.put(Constants.Files.APP_MASTER_JAR, createLocalFile(Constants.Files.APP_MASTER_JAR, location));
    } catch (ClassNotFoundException e) {
      throw Throwables.propagate(e);
    }
  }

  private void createContainerJar(ApplicationBundler bundler, Map<String, LocalFile> localFiles) throws IOException {
    try {
      Set<Class<?>> classes = Sets.newIdentityHashSet();
      classes.add(TwillContainerMain.class);
      classes.addAll(dependencies);

      ClassLoader classLoader = getClassLoader();
      for (RuntimeSpecification spec : twillSpec.getRunnables().values()) {
        classes.add(classLoader.loadClass(spec.getRunnableSpecification().getClassName()));
      }

      LOG.debug("Create and copy {}", Constants.Files.CONTAINER_JAR);
      Location location = createTempLocation(Constants.Files.CONTAINER_JAR);
      bundler.createBundle(location, classes, resources);
      LOG.debug("Done {}", Constants.Files.CONTAINER_JAR);

      localFiles.put(Constants.Files.CONTAINER_JAR, createLocalFile(Constants.Files.CONTAINER_JAR, location));

    } catch (ClassNotFoundException e) {
      throw Throwables.propagate(e);
    }
  }

  /**
   * Based on the given {@link TwillSpecification}, upload LocalFiles to Yarn Cluster.
   * @param twillSpec The {@link TwillSpecification} for populating resource.
   * @param localFiles A Multimap to store runnable name to transformed LocalFiles.
   * @throws IOException
   */
  private void populateRunnableLocalFiles(TwillSpecification twillSpec,
                                          Multimap<String, LocalFile> localFiles) throws IOException {

    LOG.debug("Populating Runnable LocalFiles");
    for (Map.Entry<String, RuntimeSpecification> entry: twillSpec.getRunnables().entrySet()) {
      String runnableName = entry.getKey();
      for (LocalFile localFile : entry.getValue().getLocalFiles()) {
        Location location;

        URI uri = localFile.getURI();
        if ("hdfs".equals(uri.getScheme())) {
          // Assuming the location factory is HDFS one. If it is not, it will failed, which is the correct behavior.
          location = locationFactory.create(uri);
        } else {
          URL url = uri.toURL();
          LOG.debug("Create and copy {} : {}", runnableName, url);
          // Preserves original suffix for expansion.
          location = copyFromURL(url, createTempLocation(Paths.appendSuffix(url.getFile(), localFile.getName())));
          LOG.debug("Done {} : {}", runnableName, url);
        }

        localFiles.put(runnableName,
                       new DefaultLocalFile(localFile.getName(), location.toURI(), location.lastModified(),
                                            location.length(), localFile.isArchive(), localFile.getPattern()));
      }
    }
    LOG.debug("Done Runnable LocalFiles");
  }

  private void saveSpecification(TwillSpecification spec, final Multimap<String, LocalFile> runnableLocalFiles,
                                 Map<String, LocalFile> localFiles) throws IOException {
    // Rewrite LocalFiles inside twillSpec
    Map<String, RuntimeSpecification> runtimeSpec = Maps.transformEntries(
      spec.getRunnables(), new Maps.EntryTransformer<String, RuntimeSpecification, RuntimeSpecification>() {
      @Override
      public RuntimeSpecification transformEntry(String key, RuntimeSpecification value) {
        return new DefaultRuntimeSpecification(value.getName(), value.getRunnableSpecification(),
                                               value.getResourceSpecification(), runnableLocalFiles.get(key));
      }
    });

    // Serialize into a local temp file.
    LOG.debug("Create and copy {}", Constants.Files.TWILL_SPEC);
    Location location = createTempLocation(Constants.Files.TWILL_SPEC);
    Writer writer = new OutputStreamWriter(location.getOutputStream(), Charsets.UTF_8);
    try {
      EventHandlerSpecification eventHandler = spec.getEventHandler();
      if (eventHandler == null) {
        eventHandler = new LogOnlyEventHandler().configure();
      }

      TwillSpecificationAdapter.create().toJson(
        new DefaultTwillSpecification(spec.getName(), runtimeSpec, spec.getOrders(), eventHandler),
        writer);
    } finally {
      writer.close();
    }
    LOG.debug("Done {}", Constants.Files.TWILL_SPEC);

    localFiles.put(Constants.Files.TWILL_SPEC, createLocalFile(Constants.Files.TWILL_SPEC, location));
  }

  private void saveLogback(Map<String, LocalFile> localFiles) throws IOException {
    LOG.debug("Create and copy {}", Constants.Files.LOGBACK_TEMPLATE);
    Location location = copyFromURL(getClass().getClassLoader().getResource(Constants.Files.LOGBACK_TEMPLATE),
                                    createTempLocation(Constants.Files.LOGBACK_TEMPLATE));
    LOG.debug("Done {}", Constants.Files.LOGBACK_TEMPLATE);

    localFiles.put(Constants.Files.LOGBACK_TEMPLATE, createLocalFile(Constants.Files.LOGBACK_TEMPLATE, location));
  }

  /**
   * Creates the launcher.jar for launch the main application.
   */
  private void saveLauncher(Map<String, LocalFile> localFiles) throws URISyntaxException, IOException {

    LOG.debug("Create and copy {}", Constants.Files.LAUNCHER_JAR);
    Location location = createTempLocation(Constants.Files.LAUNCHER_JAR);

    final String launcherName = TwillLauncher.class.getName();

    // Create a jar file with the TwillLauncher optionally a json serialized classpath.json in it.
    final JarOutputStream jarOut = new JarOutputStream(location.getOutputStream());
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    if (classLoader == null) {
      classLoader = getClass().getClassLoader();
    }
    Dependencies.findClassDependencies(classLoader, new Dependencies.ClassAcceptor() {
      @Override
      public boolean accept(String className, URL classUrl, URL classPathUrl) {
        Preconditions.checkArgument(className.startsWith(launcherName),
                                    "Launcher jar should not have dependencies: %s", className);
        try {
          jarOut.putNextEntry(new JarEntry(className.replace('.', '/') + ".class"));
          InputStream is = classUrl.openStream();
          try {
            ByteStreams.copy(is, jarOut);
          } finally {
            is.close();
          }
        } catch (IOException e) {
          throw Throwables.propagate(e);
        }
        return true;
      }
    }, TwillLauncher.class.getName());

    try {
      if (!classPaths.isEmpty()) {
        jarOut.putNextEntry(new JarEntry("classpath"));
        jarOut.write(Joiner.on(':').join(classPaths).getBytes(Charsets.UTF_8));
      }
    } finally {
      jarOut.close();
    }
    LOG.debug("Done {}", Constants.Files.LAUNCHER_JAR);

    localFiles.put(Constants.Files.LAUNCHER_JAR, createLocalFile(Constants.Files.LAUNCHER_JAR, location));
  }

  private void saveVmOptions(String opts, Map<String, LocalFile> localFiles) throws IOException {
    if (opts.isEmpty()) {
      // If no vm options, no need to localize the file.
      return;
    }
    LOG.debug("Copy {}", Constants.Files.JVM_OPTIONS);
    final Location location = createTempLocation(Constants.Files.JVM_OPTIONS);
    CharStreams.write(opts, new OutputSupplier<Writer>() {
      @Override
      public Writer getOutput() throws IOException {
        return new OutputStreamWriter(location.getOutputStream(), Charsets.UTF_8);
      }
    });
    LOG.debug("Done {}", Constants.Files.JVM_OPTIONS);

    localFiles.put(Constants.Files.JVM_OPTIONS, createLocalFile(Constants.Files.JVM_OPTIONS, location));
  }

  private void saveArguments(Arguments arguments, Map<String, LocalFile> localFiles) throws IOException {
    LOG.debug("Create and copy {}", Constants.Files.ARGUMENTS);
    final Location location = createTempLocation(Constants.Files.ARGUMENTS);
    ArgumentsCodec.encode(arguments, new OutputSupplier<Writer>() {
      @Override
      public Writer getOutput() throws IOException {
        return new OutputStreamWriter(location.getOutputStream(), Charsets.UTF_8);
      }
    });
    LOG.debug("Done {}", Constants.Files.ARGUMENTS);

    localFiles.put(Constants.Files.ARGUMENTS, createLocalFile(Constants.Files.ARGUMENTS, location));
  }

  /**
   * Serializes the list of files that needs to localize from AM to Container.
   */
  private void saveLocalFiles(Map<String, LocalFile> localFiles, Set<String> includes) throws IOException {
    Map<String, LocalFile> localize = ImmutableMap.copyOf(Maps.filterKeys(localFiles, Predicates.in(includes)));
    LOG.debug("Create and copy {}", Constants.Files.LOCALIZE_FILES);
    Location location = createTempLocation(Constants.Files.LOCALIZE_FILES);
    Writer writer = new OutputStreamWriter(location.getOutputStream(), Charsets.UTF_8);
    try {
      new GsonBuilder().registerTypeAdapter(LocalFile.class, new LocalFileCodec())
        .create().toJson(localize.values(), new TypeToken<List<LocalFile>>() {
      }.getType(), writer);
    } finally {
      writer.close();
    }
    LOG.debug("Done {}", Constants.Files.LOCALIZE_FILES);
    localFiles.put(Constants.Files.LOCALIZE_FILES, createLocalFile(Constants.Files.LOCALIZE_FILES, location));
  }

  private Location copyFromURL(URL url, Location target) throws IOException {
    InputStream is = url.openStream();
    try {
      OutputStream os = new BufferedOutputStream(target.getOutputStream());
      try {
        ByteStreams.copy(is, os);
      } finally {
        os.close();
      }
    } finally {
      is.close();
    }
    return target;
  }

  private Location createTempLocation(String fileName) {
    String name;
    String suffix = Paths.getExtension(fileName);

    name = fileName.substring(0, fileName.length() - suffix.length() - 1);

    try {
      return getAppLocation().append(name).getTempFile('.' + suffix);
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  private Location getAppLocation() {
    return locationFactory.create(String.format("/%s/%s", twillSpec.getName(), runId.getId()));
  }

  /**
   * Returns the context ClassLoader if there is any, otherwise, returns ClassLoader of this class.
   */
  private ClassLoader getClassLoader() {
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    return classLoader == null ? getClass().getClassLoader() : classLoader;
  }
}