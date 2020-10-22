package org.intocps.maestro.webapi.maestro2;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.intocps.maestro.ErrorReporter;
import org.intocps.maestro.ast.LexIdentifier;
import org.intocps.maestro.core.Framework;
import org.intocps.maestro.core.api.FixedStepSizeAlgorithm;
import org.intocps.maestro.core.messages.IErrorReporter;
import org.intocps.maestro.framework.fmi2.ComponentInfo;
import org.intocps.maestro.framework.fmi2.Fmi2SimulationEnvironment;
import org.intocps.maestro.framework.fmi2.Fmi2SimulationEnvironmentConfiguration;
import org.intocps.maestro.template.MaBLTemplateConfiguration;
import org.intocps.maestro.webapi.controllers.ProdSessionLogicFactory;
import org.intocps.maestro.webapi.controllers.SessionController;
import org.intocps.maestro.webapi.controllers.SessionLogic;
import org.intocps.orchestration.coe.cosim.BasicFixedStepSizeCalculator;
import org.intocps.orchestration.coe.cosim.CoSimStepSizeCalculator;
import org.intocps.orchestration.coe.httpserver.Algorithm;
import org.intocps.orchestration.coe.json.InitializationMsgJson;
import org.intocps.orchestration.coe.modeldefinition.ModelDescription;
import org.intocps.orchestration.coe.util.ZipDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipOutputStream;


@RestController
@Component
public class Maestro2SimulationController {

    public static final SessionController sessionController = new SessionController(new ProdSessionLogicFactory());
    final static ObjectMapper mapper = new ObjectMapper();
    private final static Logger logger = LoggerFactory.getLogger(Maestro2SimulationController.class);

    public static InitializationMsgJson.Constraint convert(IVarStepConstraint constraint) {
        if (constraint instanceof InitializationData.FmuMaxStepSizeConstraint) {
            InitializationMsgJson.Constraint c = new InitializationMsgJson.Constraint();
            c.type = "fmumaxstepsize";
            return c;

        } else if (constraint instanceof InitializationData.BoundedDifferenceConstraint) {
            InitializationData.BoundedDifferenceConstraint cIn = (InitializationData.BoundedDifferenceConstraint) constraint;
            InitializationMsgJson.Constraint c = new InitializationMsgJson.Constraint();
            c.type = "boundeddifference";
            c.abstol = cIn.abstol;
            c.ports = cIn.ports;
            c.reltol = cIn.reltol;
            c.safety = cIn.safety;
            c.skipDiscrete = cIn.skipDiscrete;
            return c;

        } else if (constraint instanceof InitializationData.SamplingConstraint) {
            InitializationData.SamplingConstraint cIn = (InitializationData.SamplingConstraint) constraint;
            InitializationMsgJson.Constraint c = new InitializationMsgJson.Constraint();
            c.type = "samplingrate";
            c.base = cIn.base;
            c.rate = cIn.rate;
            c.startTime = cIn.startTime;
            return c;

        } else if (constraint instanceof InitializationData.ZeroCrossingConstraint) {
            InitializationData.ZeroCrossingConstraint cIn = (InitializationData.ZeroCrossingConstraint) constraint;
            InitializationMsgJson.Constraint c = new InitializationMsgJson.Constraint();
            c.type = "zerocrossing";
            c.abstol = cIn.abstol;
            c.ports = cIn.ports;
            c.order = cIn.order;
            c.safety = cIn.safety;
            return c;
        }
        return null;
    }

    public void overrideRootLoggerLogLevel(Level level) {
        if (level == null) {
            return;
        }
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        LoggerConfig loggerConfig = config.getLoggerConfig(LogManager.ROOT_LOGGER_NAME);
        loggerConfig.setLevel(level);
        ctx.updateLoggers();
    }

    @RequestMapping(value = "/upload/{sessionId}", method = RequestMethod.POST)
    public void uploadFile(@PathVariable String sessionId,
            @ApiParam(value = "File", required = true) @RequestParam("fieldFile") MultipartFile file) throws IOException {
        throw new NotImplementedException("/upload/{sessionId} has not been implemented.");
        //        try (InputStream is = file.getInputStream()) {
        //            logger.debug("Uploaded file: {}", file.getOriginalFilename());
        //            File targetFile = new File(sessions.get(sessionId).getResultRoot(), file.getOriginalFilename());
        //            IOUtils.copy(is, new FileOutputStream(targetFile));
        //        }

    }

    @RequestMapping(value = "/ping", method = RequestMethod.GET)
    public String ping() {
        return "OK";
    }

    @RequestMapping(value = "/status/{sessionId}", method = RequestMethod.GET)
    public List<StatusModel> getStatuses(@PathVariable String sessionId) {
        throw new NotImplementedException("/status/{sessionId} has not been implemented.");
    }

    StatusModel getStatus(String sessionId) {
        if (sessionController.containsSession(sessionId)) {
            return new StatusModel("Session exists", sessionId, 0);
        } else {
            return new StatusModel("Session does not exist", sessionId, 0);
        }
    }

    @RequestMapping(value = "/createSession", method = RequestMethod.GET)
    public StatusModel createSession() {
        String session = sessionController.createNewSession();
        return getStatus(session);
    }

    @RequestMapping(value = "/initialize/{sessionId}", method = RequestMethod.POST)
    public InitializeStatusModel initializeSession(@PathVariable String sessionId, @RequestBody String body1) throws Exception {
        // Store this data to be used for the interpretor later on.
        // It is not possible to create the spec at this point in time as data for setup experiment is missing (i.e. endtime)
        //        logger.debug("Got initial data: {}", new ObjectMapper().writeValueAsString(body1));
        logger.debug("Got initial data: {}", body1);
        SessionLogic logic = sessionController.getSessionLogic(sessionId);
        mapper.writeValue(new File(logic.rootDirectory, "initialize.json"), body1);
        ObjectMapper mapper = new ObjectMapper();//.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        InitializationData body = mapper.readValue(body1, InitializationData.class);


        if (logic == null) {
            throw new Exception("Session has not been created.");
        }

        if (body == null) {
            throw new Exception("Could not parse configuration: ");
        }

        if (body.overrideLogLevel != null) {
            overrideRootLoggerLogLevel(convertLogLevel(body.overrideLogLevel));
        }

        if (body.fmus == null) {
            throw new Exception("FMUs must not be null");
        }

        if (body.connections == null) {
            throw new Exception("Connections must not be null");
        }

        CoSimStepSizeCalculator stepSizeCalculator = null;
        Algorithm stepAlgorithm = Algorithm.NONE;
        if (body.algorithm == null) {

            stepAlgorithm = Algorithm.FIXED;
            stepSizeCalculator = new BasicFixedStepSizeCalculator(0.1);
            logger.info("No step size algorithm given. Defaulting to fixed-step with size 0.1");

        } else if (body.algorithm instanceof FixedStepAlgorithmConfig) {
            FixedStepAlgorithmConfig algorithm = (FixedStepAlgorithmConfig) body.algorithm;

            if (algorithm.size == null) {
                throw new Exception("fixed-step size must be an integer or double");
            }

            logger.info("Using Fixed-step size calculator with size = {}", algorithm.size);
            stepSizeCalculator = new BasicFixedStepSizeCalculator(algorithm.size);
            stepAlgorithm = Algorithm.FIXED;
        } else if (body.algorithm instanceof VariableStepAlgorithmConfig) {
            logger.info("Variable step algorithm not supported");
            throw new NotImplementedException("Variable step algorithms are not supported.");
        }
        Map<String, List<ModelDescription.LogCategory>> logs = null;

        if (body.stabalizationEnabled) {

            //            if (body.global_absolute_tolerance != 0.0) {
            //                throw new NotImplementedException("global absolute tolerance is not implemented");
            //            }
            //            if (body.global_relative_tolerance != 0.0) {
            //                throw new NotImplementedException("global absolute tolerance is not implemented");
            //            }
            throw new NotImplementedException("Stabilisation is not implemented");
        }
        if (body.parallelSimulation) {
            throw new NotImplementedException("ParallelSimulation is not implemented");
        }
        if (body.simulationProgramDelay) {
            throw new NotImplementedException("SimulationProgramDelay is not implemented");
        }

        if (body.hasExternalSignals) {
            throw new NotImplementedException("HasExternalSignals is not implemented");
        }


        logger.trace("Initialization completed");
        logic.setInitializationData(body);


        return new InitializeStatusModel("initialized", sessionId, null, 0);
    }

    private Level convertLogLevel(InitializationData.InitializeLogLevel overrideLogLevel) {
        switch (overrideLogLevel) {

            case OFF:
                return Level.OFF;
            case FATAL:
                return Level.FATAL;
            case ERROR:
                return Level.ERROR;
            case WARN:
                return Level.WARN;
            case INFO:
                return Level.INFO;
            case DEBUG:
                return Level.DEBUG;
            case TRACE:
                return Level.TRACE;
            case ALL:
                return Level.ALL;
        }
        return null;
    }


    @ApiOperation(value = "This request begins the co-simulation")
    @RequestMapping(value = "/simulate/{sessionId}", method = RequestMethod.POST, consumes = {"text/plain", "application/json"})
    public StatusModel simulate(@PathVariable String sessionId, @RequestBody SimulateRequestBody body) throws Exception {
        SessionLogic logic = sessionController.getSessionLogic(sessionId);
        mapper.writeValue(new File(logic.rootDirectory, "simulate.json"), body);

        InitializationData initializeRequest = logic.getInitializationData();

        Map<String, Object> initialize = new HashMap<>();
        initialize.put("parameters", initializeRequest.parameters);

        ErrorReporter reporter = new ErrorReporter();


        Fmi2SimulationEnvironmentConfiguration simulationConfiguration = new Fmi2SimulationEnvironmentConfiguration();
        simulationConfiguration.fmus = initializeRequest.getFmus();
        simulationConfiguration.connections = initializeRequest.getConnections();
        simulationConfiguration.logVariables = initializeRequest.getLogVariables();
        simulationConfiguration.livestream = initializeRequest.livestream;

        Fmi2SimulationEnvironment simulationEnvironment = Fmi2SimulationEnvironment.of(simulationConfiguration, new IErrorReporter.SilentReporter());

        // Loglevels from app consists of {key}.instance: [loglevel1, loglevel2,...] but have to be: instance: [loglevel1, loglevel2,...].
        Map<String, List<String>> removedFMUKeyFromLogLevels = body.logLevels.entrySet().stream().collect(Collectors
                .toMap(entry -> MaBLTemplateConfiguration.MaBLTemplateConfigurationBuilder.getFmuInstanceFromFmuKeyInstance(entry.getKey()),
                        Map.Entry::getValue));

        MaBLTemplateConfiguration.MaBLTemplateConfigurationBuilder builder =
                MaBLTemplateConfiguration.MaBLTemplateConfigurationBuilder.getBuilder().setFrameworkConfig(Framework.FMI2, simulationConfiguration)
                        .useInitializer(true, new ObjectMapper().writeValueAsString(initialize)).setFramework(Framework.FMI2)
                        .setLogLevels(removedFMUKeyFromLogLevels).setVisible(initializeRequest.visible).setLoggingOn(initializeRequest.loggingOn).
                        setStepAlgorithm(
                                new FixedStepSizeAlgorithm(body.endTime, ((FixedStepAlgorithmConfig) initializeRequest.getAlgorithm()).getSize()));

        Maestro2Broker mc = new Maestro2Broker(logic.rootDirectory);
        MaBLTemplateConfiguration configuration = builder.build();
        mc.generateSpecification(configuration);

        Function<Map<String, List<String>>, List<String>> flattenFmuIds =
                map -> map.entrySet().stream().flatMap(entry -> entry.getValue().stream().map(v -> entry.getKey() + "." + v))
                        .collect(Collectors.toList());


        List<String> connectedOutputs = simulationEnvironment.getConnectedOutputs().stream().map(x -> {
            ComponentInfo i = simulationEnvironment.getUnitInfo(new LexIdentifier(x.instance.getText(), null), Framework.FMI2);
            return String.format("%s.%s.%s", i.fmuIdentifier, x.instance.getText(), x.scalarVariable.getName());
        }).collect(Collectors.toList());


        mc.executeInterpreter(logic.getSocket(), Stream.concat(connectedOutputs.stream(),
                (initializeRequest.logVariables == null ? new Vector<String>() : flattenFmuIds.apply(initializeRequest.logVariables)).stream())
                        .collect(Collectors.toList()),
                initializeRequest.livestream == null ? new Vector<>() : flattenFmuIds.apply(initializeRequest.livestream), body.liveLogInterval);

        return getStatus(sessionId);
    }


    @RequestMapping(value = "/stopsimulation/{sessionId}", method = RequestMethod.POST)
    public void stop(@PathVariable String sessionId) {
        throw new NotImplementedException("/stopsimulation/{sessionId} has not been implemented.");
        //        if (sessions.containsKey(sessionId)) {
        //            sessions.get(sessionId).stopSimulation();
        //        }
    }

    @RequestMapping(value = "/result/{sessionId}/plain", method = RequestMethod.GET)
    public ResponseEntity<Resource> getResultPlain(@PathVariable String sessionId) throws Exception {
        SessionLogic sessionLogic = this.sessionController.getSessionLogic(sessionId);

        if (sessionLogic == null) {
            throw new IllegalArgumentException("The session with id: " + sessionId + " does not exist.");
        }

        ByteArrayResource resource = new ByteArrayResource(FileUtils.readFileToByteArray(new File(sessionLogic.rootDirectory, "outputs.csv")));
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + "outputs.csv" + "\"").body(resource);
    }

    @RequestMapping(value = "/result/{sessionId}/zip", method = RequestMethod.GET, produces = "application/zip")
    public void getResultZip(@PathVariable String sessionId, HttpServletResponse response) throws Exception {
        SessionLogic sessionLogic = this.sessionController.getSessionLogic(sessionId);

        if (sessionLogic == null) {
            throw new IllegalArgumentException("The session with id: " + sessionId + " does not exist.");
        }

        //setting headers
        response.setStatus(HttpServletResponse.SC_OK);
        response.addHeader("Content-Disposition", "attachment; filename=\"results.zip\"");
        //
        ZipOutputStream zipOutputStream = new ZipOutputStream(response.getOutputStream());
        ZipDirectory.addDir(sessionLogic.rootDirectory, sessionLogic.rootDirectory, zipOutputStream);
        zipOutputStream.close();
    }

    @RequestMapping(value = "/destroy/{sessionId}", method = RequestMethod.GET)
    public void destroy(@PathVariable String sessionId) throws Exception {
        this.sessionController.deleteSession(sessionId);
    }

    @RequestMapping(value = "/version", method = RequestMethod.GET)
    public String version() {
        final String message = "{\"version\":\"2.0.0-alpha\"}";
        return message;
    }

    @ApiModel(subTypes = {FixedStepAlgorithmConfig.class, VariableStepAlgorithmConfig.class}, discriminator = "type",
            description = "Simulation algorithm.")
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes(
            {@Type(value = FixedStepAlgorithmConfig.class, name = "fixed-step"), @Type(value = VariableStepAlgorithmConfig.class, name = "var-step")})
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public interface IAlgorithmConfig {
    }

    @ApiModel(subTypes = {InitializationData.BoundedDifferenceConstraint.class, InitializationData.ZeroCrossingConstraint.class,
            InitializationData.SamplingConstraint.class, InitializationData.FmuMaxStepSizeConstraint.class}, discriminator = "type",
            description = "Simulation variable step algorithm constraint.", value = "VarStepConstraint")

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", include = JsonTypeInfo.As.PROPERTY, visible = true)
    @JsonSubTypes({@Type(value = InitializationData.BoundedDifferenceConstraint.class, name = "boundeddifference"),
            @Type(value = InitializationData.ZeroCrossingConstraint.class, name = "zerocrossing"),
            @Type(value = InitializationData.SamplingConstraint.class, name = "samplingrate"),
            @Type(value = InitializationData.FmuMaxStepSizeConstraint.class, name = "fmumaxstepsize")})
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public interface IVarStepConstraint {

        void validate() throws Exception;
    }

    //    @RequestMapping(value = "/reset/{sessionId}", method = RequestMethod.GET)
    //    public void reset(@PathVariable String sessionId) {
    //
    //    }


    public static class SimulateRequestBody {
        @ApiModelProperty(value = "The start time of the co-simulation")
        @JsonProperty("startTime")
        final double startTime;
        @JsonProperty("endTime")
        final double endTime;
        @JsonProperty("logLevels")
        final Map<String, List<String>> logLevels;
        @JsonProperty("reportProgress")
        final Boolean reportProgress;
        @JsonProperty("liveLogInterval")
        final Double liveLogInterval;

        @JsonCreator
        public SimulateRequestBody(@JsonProperty("startTime") double startTime, @JsonProperty("endTime") double endTime,
                @JsonProperty("logLevels") Map<String, List<String>> logLevels, @JsonProperty("reportProgress") Boolean reportProgress,
                @JsonProperty("liveLogInterval") Double liveLogInterval) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.logLevels = logLevels;
            this.reportProgress = reportProgress;
            this.liveLogInterval = liveLogInterval;
        }

        public double getStartTime() {
            return startTime;
        }

        public double getEndTime() {
            return endTime;
        }
    }

    public static class StatusModel {
        @JsonProperty("status")
        public String status;
        @JsonProperty("sessionId")
        public String sessionId;

        @JsonProperty("lastExecTime")
        public long lastExecTime;

        public StatusModel() {
        }

        public StatusModel(String status, String sessionId, long lastExecTime) {
            this.status = status;
            this.sessionId = sessionId;
            this.lastExecTime = lastExecTime;
        }
    }

    public static class InitializeStatusModel extends StatusModel {

        @JsonProperty("avaliableLogLevels")
        private final Map<String, List<LogLevelModel>> avaliableLogLevels;

        @JsonCreator
        public InitializeStatusModel(@JsonProperty("status") String status, @JsonProperty("sessionid") String sessionId,
                @JsonProperty("avaliableLogLevels") Map<String, List<LogLevelModel>> avaliableLogLevels,
                @JsonProperty("lastExecTime") final long lastExecTime) {
            super(status, sessionId, lastExecTime);
            this.avaliableLogLevels = avaliableLogLevels;
        }

        public static class LogLevelModel {
            final String name;
            final String description;

            public LogLevelModel(String name, String description) {
                this.name = name;
                this.description = description;
            }
        }
    }

    @ApiModel(parent = IAlgorithmConfig.class)
    public static class FixedStepAlgorithmConfig implements IAlgorithmConfig {
        @JsonProperty("size")
        public final Double size;

        @JsonCreator
        public FixedStepAlgorithmConfig(@JsonProperty("size") Double size) {
            this.size = size;
        }

        public Double getSize() {
            return size;
        }
    }

    @ApiModel(parent = IAlgorithmConfig.class)
    public static class VariableStepAlgorithmConfig implements IAlgorithmConfig {

        @JsonProperty("size")
        final Double[] size;
        @JsonProperty("initsize")
        final Double initsize;
        @JsonProperty("constraints")
        final Map<String, IVarStepConstraint> constraints;

        public VariableStepAlgorithmConfig(@JsonProperty("size") Double[] size, @JsonProperty("initsize") Double initsize,
                @JsonProperty("constraints") final Map<String, IVarStepConstraint> constraints) {
            this.size = size;
            this.initsize = initsize;
            this.constraints = constraints;
        }

        public Double[] getSize() {
            return size;
        }

        public Double getInitsize() {
            return initsize;
        }

        public Map<String, IVarStepConstraint> getConstraints() {
            return constraints;
        }

    }

    public static class InitializationData {

        @JsonIgnore
        @JsonProperty("liveGraphColumns")
        final Object liveGraphColumns = null;

        @JsonIgnore
        @JsonProperty("liveGraphVisibleRowCount")
        final Object liveGraphVisibleRowCount = null;

        @JsonProperty("fmus")
        final Map<String, String> fmus;
        @JsonProperty("connections")
        final Map<String, List<String>> connections;
        @JsonProperty("parameters")
        final Map<String, Object> parameters;
        @JsonProperty("livestream")
        final Map<String, List<String>> livestream;
        @JsonProperty("logVariables")
        final Map<String, List<String>> logVariables;
        @JsonProperty("parallelSimulation")
        final boolean parallelSimulation;
        @JsonProperty("stabalizationEnabled")
        final boolean stabalizationEnabled;
        @JsonProperty("global_absolute_tolerance")
        final double global_absolute_tolerance;
        @JsonProperty("global_relative_tolerance")
        final double global_relative_tolerance;
        @JsonProperty("loggingOn")
        final boolean loggingOn;
        @JsonProperty("visible")
        final boolean visible;
        @JsonProperty("simulationProgramDelay")
        final boolean simulationProgramDelay;
        @JsonProperty("hasExternalSignals")
        final boolean hasExternalSignals;
        @JsonProperty("overrideLogLevel")
        final InitializeLogLevel overrideLogLevel;
        @JsonProperty("algorithm")
        IAlgorithmConfig algorithm;

        @JsonCreator
        public InitializationData(@JsonProperty("fmus") Map<String, String> fmus, @JsonProperty("connections") Map<String, List<String>> connections,
                @JsonProperty("parameters") Map<String, Object> parameters, @JsonProperty("livestream") Map<String, List<String>> livestream,
                @JsonProperty("logVariables") Map<String, List<String>> logVariables, @JsonProperty("parallelSimulation") boolean parallelSimulation,
                @JsonProperty("stabalizationEnabled") boolean stabalizationEnabled,
                @JsonProperty("global_absolute_tolerance") double global_absolute_tolerance,
                @JsonProperty("global_relative_tolerance") double global_relative_tolerance, @JsonProperty("loggingOn") boolean loggingOn,
                @JsonProperty("visible") boolean visible, @JsonProperty("simulationProgramDelay") boolean simulationProgramDelay,
                @JsonProperty("hasExternalSignals") boolean hasExternalSignals, @JsonProperty("algorithm") IAlgorithmConfig algorithm,
                @JsonProperty("overrideLogLevel") final InitializeLogLevel overrideLogLevel,
                @JsonProperty("liveGraphColumns") final Object liveGraphColumns,
                @JsonProperty("liveGraphVisibleRowCount") final Object liveGraphVisibleRowCount) {
            this.fmus = fmus;
            this.connections = connections;
            this.parameters = parameters;
            this.livestream = livestream;
            this.logVariables = logVariables;
            this.loggingOn = loggingOn;
            this.visible = visible;
            this.simulationProgramDelay = simulationProgramDelay;
            this.hasExternalSignals = hasExternalSignals;
            this.parallelSimulation = parallelSimulation;
            this.stabalizationEnabled = stabalizationEnabled;
            this.global_absolute_tolerance = global_absolute_tolerance;
            this.global_relative_tolerance = global_relative_tolerance;
            this.algorithm = algorithm;
            this.overrideLogLevel = overrideLogLevel;
        }

        public InitializeLogLevel getOverrideLogLevel() {
            return overrideLogLevel;
        }

        public Map<String, String> getFmus() {
            return fmus;
        }

        public Map<String, List<String>> getConnections() {
            return connections;
        }

        public Map<String, Object> getParameters() {
            return parameters;
        }

        public Map<String, List<String>> getLivestream() {
            return livestream;
        }

        public Map<String, List<String>> getLogVariables() {
            return logVariables;
        }

        public boolean isParallelSimulation() {
            return parallelSimulation;
        }

        public boolean isStabalizationEnabled() {
            return stabalizationEnabled;
        }

        public double getGlobal_absolute_tolerance() {
            return global_absolute_tolerance;
        }

        public double getGlobal_relative_tolerance() {
            return global_relative_tolerance;
        }

        public boolean isLoggingOn() {
            return loggingOn;
        }

        public boolean isVisible() {
            return visible;
        }

        public boolean isSimulationProgramDelay() {
            return simulationProgramDelay;
        }

        public boolean isHasExternalSignals() {
            return hasExternalSignals;
        }

        public IAlgorithmConfig getAlgorithm() {
            return algorithm;
        }

        @JsonIgnore
        public Map<String, URI> getFmuFiles() throws Exception {
            Map<String, URI> files = new HashMap<>();

            if (fmus != null) {
                for (Map.Entry<String, String> entry : fmus.entrySet()) {
                    try {
                        files.put(entry.getKey(), new URI(entry.getValue()));
                    } catch (Exception e) {
                        throw new Exception(entry.getKey() + "-" + entry.getValue() + ": " + e.getMessage(), e);
                    }
                }
            }

            return files;
        }

        enum InitializeLogLevel {
            OFF,
            FATAL,
            ERROR,
            WARN,
            INFO,
            DEBUG,
            TRACE,
            ALL
        }

        @ApiModel(parent = IVarStepConstraint.class)
        public static class SamplingConstraint implements IVarStepConstraint {
            Integer base;
            Integer rate;
            Integer startTime;

            public SamplingConstraint() {
            }

            public SamplingConstraint(Integer base, Integer rate, Integer startTime) {
                this.base = base;
                this.rate = rate;
                this.startTime = startTime;
            }

            public Integer getBase() {
                return base;
            }

            public Integer getRate() {
                return rate;
            }

            public Integer getStartTime() {
                return startTime;
            }

            @Override
            public void validate() throws Exception {

            }
        }

        @ApiModel(parent = IVarStepConstraint.class)
        public static class FmuMaxStepSizeConstraint implements IVarStepConstraint {

            @Override
            public void validate() throws Exception {

            }
        }

        @ApiModel(parent = IVarStepConstraint.class)
        public static class BoundedDifferenceConstraint implements IVarStepConstraint {
            List<String> ports;
            Double reltol;
            Double abstol;
            Double safety;
            Boolean skipDiscrete;

            public BoundedDifferenceConstraint() {
            }

            public BoundedDifferenceConstraint(List<String> ports, Double reltol, Double abstol, Double safety, Boolean skipDiscrete) {
                this.ports = ports;
                this.reltol = reltol;
                this.abstol = abstol;
                this.safety = safety;
                this.skipDiscrete = skipDiscrete;
            }

            public List<String> getPorts() {
                return ports;
            }

            public Double getReltol() {
                return reltol;
            }

            public Double getAbstol() {
                return abstol;
            }

            public Double getSafety() {
                return safety;
            }

            public Boolean getSkipDiscrete() {
                return skipDiscrete;
            }

            @Override
            public void validate() throws Exception {

            }
        }

        @ApiModel(parent = IVarStepConstraint.class)
        public static class ZeroCrossingConstraint implements IVarStepConstraint {
            List<String> ports;
            Integer order;
            Double abstol;
            Double safety;

            public ZeroCrossingConstraint() {
            }

            public ZeroCrossingConstraint(List<String> ports, Integer order, Double abstol, Double safety) {
                this.ports = ports;
                this.order = order;
                this.abstol = abstol;
                this.safety = safety;
            }

            public List<String> getPorts() {
                return ports;
            }

            public Integer getOrder() {
                return order;
            }

            public Double getAbstol() {
                return abstol;
            }

            public Double getSafety() {
                return safety;
            }

            @Override
            public void validate() throws Exception {

            }
        }

        //    @RequestMapping(value = "", method = RequestMethod.POST)
        //    public void createField(@RequestBody FieldRequest fieldRequest, Principal principal) throws Exception {
        //        int tenantId = tenantDataService.getTenantId(principal.getName());
        //        logger.debug("Creating field, user {}, tenant id {}", principal.getName(), tenantId);
        //        com.agcocorp.logistics.resources.model.FieldConfiguration mapped = buildField(fieldRequest);
        //        com.agcocorp.logistics.resources.model.Field created = service.create(tenantId, mapped);
        //        return modelMapperService.getModelMapper().map(created);
        //    }
    }
}