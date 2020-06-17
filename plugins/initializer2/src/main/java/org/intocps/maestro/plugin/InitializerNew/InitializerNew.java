package org.intocps.maestro.plugin.InitializerNew;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.intocps.maestro.ast.*;
import org.intocps.maestro.core.Framework;
import org.intocps.maestro.core.messages.IErrorReporter;
import org.intocps.maestro.plugin.IMaestroUnfoldPlugin;
import org.intocps.maestro.plugin.IPluginConfiguration;
import org.intocps.maestro.plugin.InitializerNew.ConversionUtilities.BooleanUtils;
import org.intocps.maestro.plugin.InitializerNew.ConversionUtilities.LongUtils;
import org.intocps.maestro.plugin.InitializerNew.Spec.StatementGeneratorContainer;
import org.intocps.maestro.plugin.SimulationFramework;
import org.intocps.maestro.plugin.UnfoldException;
import org.intocps.maestro.plugin.env.ISimulationEnvironment;
import org.intocps.maestro.plugin.env.UnitRelationship;
import org.intocps.maestro.plugin.env.UnitRelationship.Variable;
import org.intocps.maestro.plugin.env.fmi2.ComponentInfo;
import org.intocps.orchestration.coe.config.InvalidVariableStringException;
import org.intocps.orchestration.coe.config.ModelConnection;
import org.intocps.orchestration.coe.config.ModelParameter;
import org.intocps.orchestration.coe.modeldefinition.ModelDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.xpath.XPathExpressionException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.intocps.maestro.ast.MableAstFactory.*;

@SimulationFramework(framework = Framework.FMI2)
public class InitializerNew implements IMaestroUnfoldPlugin {
    final static Logger logger = LoggerFactory.getLogger(InitializerNew.class);

    final AFunctionDeclaration f1 = MableAstFactory.newAFunctionDeclaration(new LexIdentifier("initialize", null),
            Arrays.asList(newAFormalParameter(newAArrayType(newANameType("FMI2Component")), newAIdentifier("component")),
                    newAFormalParameter(newAIntNumericPrimitiveType(), newAIdentifier("startTime")),
                    newAFormalParameter(newAIntNumericPrimitiveType(), newAIdentifier("endTime"))), MableAstFactory.newAVoidType());

    private final HashSet<ModelDescription.ScalarVariable> portsAlreadySet = new HashSet<>();
    Config config;
    List<ModelParameter> modelParameters;
    private final TopologicalPlugin topologicalPlugin;

    public InitializerNew() {
        this.topologicalPlugin = new TopologicalPlugin();
    }


    public InitializerNew(TopologicalPlugin topologicalPlugin) {
        this.topologicalPlugin = topologicalPlugin;
    }

    @Override
    public String getName() {
        return InitializerNew.class.getSimpleName();
    }

    @Override
    public String getVersion() {
        return "0.0.0";
    }

    @Override
    public Set<AFunctionDeclaration> getDeclaredUnfoldFunctions() {
        return Stream.of(f1).collect(Collectors.toSet());
    }

    @Override
    public PStm unfold(AFunctionDeclaration declaredFunction, List<PExp> formalArguments, IPluginConfiguration config, ISimulationEnvironment env,
            IErrorReporter errorReporter) throws UnfoldException {
        logger.info("Unfolding: {}", declaredFunction.toString());

        verifyArguments(formalArguments, env);
        final List<LexIdentifier> knownComponentNames = extractComponentNames(formalArguments);

        //Make sure the statement container doesn't container any statements
        StatementGeneratorContainer.reset();
        var sc = StatementGeneratorContainer.getInstance();
        sc.startTime = formalArguments.get(1).clone();
        sc.endTime = formalArguments.get(2).clone();
        this.config = (Config) config;
        this.modelParameters = this.config.getModelParameters();

        //Setup experiment for all components
        logger.debug("Setup experiment for all components");
        knownComponentNames.forEach(comp -> {
            sc.createSetupExperimentStatement(comp.getText(), false, 0.0, true);
        });

        //All connections - Only relations in the fashion InputToOutput is necessary since the OutputToInputs are just a dublicated of this
        Set<UnitRelationship.Relation> relations =
                env.getRelations(knownComponentNames).stream().filter(o -> o.getDirection() == UnitRelationship.Relation.Direction.OutputToInput)
                        .collect(Collectors.toSet());

        //Find the right order to instantiate dependentPorts and make sure where doesn't exist any cycles in the connections
        List<UnitRelationship.Variable> instantiationOrder = topologicalPlugin.FindInstantiationOrder(relations);

        //Set variables for all components in IniPhase
        SetComponentsVariables(env, knownComponentNames, sc, PhasePredicates.IniPhase());

        //Enter initialization Mode
        logger.debug("Enter initialization Mode");
        knownComponentNames.forEach(comp -> {
            sc.enterInitializationMode(comp.getText());
        });

        var inputToOutputRelations =
                env.getRelations(knownComponentNames).stream().filter(o -> o.getDirection() == UnitRelationship.Relation.Direction.InputToOutput)
                        .collect(Collectors.toList());

        var inputOutMapping = createInputOutputMapping(inputToOutputRelations, env);
        sc.setInputOutputMapping(inputOutMapping);

        var optimizedOrder = optimizeInstantiationOrder(instantiationOrder);

        //Initialize the ports in the correct order based on the topological sorting
        optimizedOrder.forEach(variableSet -> {
            initializePort(variableSet, sc, env);
        });

        //Exit initialization Mode
        knownComponentNames.forEach(comp -> {
            sc.exitInitializationMode(comp.getText());
        });

        var statements = sc.getStatements();
        return newABlockStm(statements);
    }

    private List<Set<Variable>> optimizeInstantiationOrder(List<Variable> instantiationOrder) {
        List<Set<Variable>> optimizedOrder = new Vector<>();
        Variable previousVariable = instantiationOrder.get(0);
        Set<Variable> currentSet = new HashSet<Variable>(Collections.singletonList(previousVariable));
        for (int i = 1; i < instantiationOrder.size(); i++) {
            Variable currentVariable = instantiationOrder.get(i);
            if (!canBeOptimized(currentVariable, previousVariable)) {
                optimizedOrder.add(currentSet);
                currentSet = new HashSet<>();
            }
            previousVariable = currentVariable;
            currentSet.add(previousVariable);
        }

        return optimizedOrder;
    }

    private boolean canBeOptimized(Variable variable1, Variable variable2) {
        return variable1.scalarVariable.getInstance() == variable2.scalarVariable.getInstance() &&
                variable2.scalarVariable.getScalarVariable().causality == variable1.scalarVariable.getScalarVariable().causality &&
                variable2.scalarVariable.getScalarVariable().getType().type == variable1.scalarVariable.getScalarVariable().getType().type;
    }

    private Map<ModelConnection.ModelInstance, Map<ModelDescription.ScalarVariable, AbstractMap.SimpleEntry<ModelConnection.ModelInstance, ModelDescription.ScalarVariable>>> createInputOutputMapping(
            List<UnitRelationship.Relation> relations, ISimulationEnvironment env) {
        Map<ModelConnection.ModelInstance, Map<ModelDescription.ScalarVariable, AbstractMap.SimpleEntry<ModelConnection.ModelInstance, ModelDescription.ScalarVariable>>>
                inputToOutputMapping = new HashMap<>();

        var relationsPerInstance = relations.stream().collect(Collectors.groupingBy(o -> o.getSource().scalarVariable.getInstance()));

        relationsPerInstance.forEach((instance, rel) -> {
            ComponentInfo infoSource = env.getUnitInfo(instance, Framework.FMI2);
            Map<ModelDescription.ScalarVariable, AbstractMap.SimpleEntry<ModelConnection.ModelInstance, ModelDescription.ScalarVariable>> entryMap =
                    new HashMap<>();
            rel.forEach(r -> {
                r.getTargets().values().forEach(v -> {
                    ComponentInfo infoTarget = env.getUnitInfo(v.scalarVariable.getInstance(), Framework.FMI2);
                    entryMap.put(r.getSource().scalarVariable.getScalarVariable(), new AbstractMap.SimpleEntry<>(
                            new ModelConnection.ModelInstance(infoTarget.fmuIdentifier, v.scalarVariable.getInstance().getText()),
                            v.scalarVariable.scalarVariable));

                });
            });
            inputToOutputMapping.put(new ModelConnection.ModelInstance(infoSource.fmuIdentifier, instance.getText()), entryMap);
        });

        return inputToOutputMapping;
    }

    //Graph doesn't contain any loops and the ports gets passed in a topological sorted order
    private void initializePort(Set<Variable> ports, StatementGeneratorContainer sc, ISimulationEnvironment env) {
        var scalarVariables = ports.stream().map(o -> o.scalarVariable.getScalarVariable()).collect(Collectors.toList());
        var type = scalarVariables.iterator().next().getType().type;
        var instance = ports.stream().findFirst().get().scalarVariable.getInstance();
        var causality = scalarVariables.iterator().next().causality;
        long[] scalarValueIndices = GetValueRefIndices(scalarVariables);

        //All members of the same set has the same causality, type and comes from the same instance
        if (causality == ModelDescription.Causality.Output) {
            getValueFromPort(sc, instance, type, scalarValueIndices);
            return;
        }
        setValueOnPort(sc, instance, type, scalarVariables, scalarValueIndices, env);
    }


    private void SetComponentsVariables(ISimulationEnvironment env, List<LexIdentifier> knownComponentNames, StatementGeneratorContainer sc,
            Predicate<ModelDescription.ScalarVariable> predicate) {
        knownComponentNames.forEach(comp -> {
            ComponentInfo info = env.getUnitInfo(comp, Framework.FMI2);
            try {
                var variablesToInitialize =
                        info.modelDescription.getScalarVariables().stream().filter(predicate.and(o -> !portsAlreadySet.contains(o)))
                                .collect(Collectors.groupingBy(o -> o.getType().type));
                if (!variablesToInitialize.isEmpty()) {
                    variablesToInitialize.forEach((type, variables) -> {
                        portsAlreadySet.addAll(variables);
                        setValueOnPort(sc, comp, type, variables, GetValueRefIndices(variables), env);
                    });
                }
            } catch (XPathExpressionException | IllegalAccessException | InvocationTargetException e) {
                logger.error(e.getMessage());
            }
        });
    }

    private long[] GetValueRefIndices(List<ModelDescription.ScalarVariable> variables) {
        return variables.stream().map(o -> o.getValueReference()).map(Long.class::cast).collect(LongUtils.TO_LONG_ARRAY);
    }

    private void setValueOnPort(StatementGeneratorContainer sc, LexIdentifier comp, ModelDescription.Types type,
            List<ModelDescription.ScalarVariable> variables, long[] scalarValueIndices, ISimulationEnvironment env) {
        ComponentInfo componentInfo = env.getUnitInfo(comp, Framework.FMI2);
        ModelConnection.ModelInstance modelInstances = new ModelConnection.ModelInstance(componentInfo.fmuIdentifier, comp.getText());

        if (type == ModelDescription.Types.Boolean) {
            sc.setBooleans(comp.getText(), scalarValueIndices,
                    Arrays.stream(GetValues(variables, modelInstances)).map(Boolean.class::cast).collect(BooleanUtils.TO_BOOLEAN_ARRAY));
        } else if (type == ModelDescription.Types.Real) {
            sc.setReals(comp.getText(), scalarValueIndices,
                    Arrays.stream(GetValues(variables, modelInstances)).mapToDouble(o -> Double.parseDouble(o.toString())).toArray());
        }

    }

    private Object[] GetValues(List<ModelDescription.ScalarVariable> variables, ModelConnection.ModelInstance modelInstance) {
        Object[] values = new Object[variables.size()];
        var i = 0;
        for (ModelDescription.ScalarVariable v : variables) {
            values[i++] = getNewValue(v, modelInstance);
        }
        return values;
    }

    private Object getNewValue(ModelDescription.ScalarVariable sv, ModelConnection.ModelInstance comp) {
        Object newVal = null;
        if (sv.type.start != null) {
            newVal = sv.type.start;
        }

        for (ModelParameter par : modelParameters) {
            if (par.variable.toString().equals(comp + "." + sv.name)) {
                newVal = par.value;
                par.isSet = true;
            }
        }
        if (sv.type.type == ModelDescription.Types.Real) {
            if (newVal instanceof Integer) {
                newVal = (double) (int) newVal;
            }
        }

        return newVal;
    }

    private void getValueFromPort(StatementGeneratorContainer sc, LexIdentifier comp, ModelDescription.Types type, long[] scalarValueIndices) {
        if (type == ModelDescription.Types.Boolean) {
            sc.getBooleans(comp.getText(), scalarValueIndices);
        } else if (type == ModelDescription.Types.Real) {
            sc.getReals(comp.getText(), scalarValueIndices);
        }
    }

    private List<LexIdentifier> extractComponentNames(List<PExp> formalArguments) throws UnfoldException {
        List<LexIdentifier> knownComponentNames = null;
        if (formalArguments.get(0) instanceof AIdentifierExp) {
            LexIdentifier name = ((AIdentifierExp) formalArguments.get(0)).getName();
            ABlockStm containingBlock = formalArguments.get(0).getAncestor(ABlockStm.class);

            Optional<AVariableDeclaration> compDecl =
                    containingBlock.getBody().stream().filter(ALocalVariableStm.class::isInstance).map(ALocalVariableStm.class::cast)
                            .map(ALocalVariableStm::getDeclaration)
                            .filter(decl -> decl.getName().equals(name) && decl.getIsArray() && decl.getInitializer() != null).findFirst();

            if (compDecl.isEmpty()) {
                throw new UnfoldException("Could not find names for comps");
            }

            AArrayInitializer initializer = (AArrayInitializer) compDecl.get().getInitializer();

            knownComponentNames = initializer.getExp().stream().filter(AIdentifierExp.class::isInstance).map(AIdentifierExp.class::cast)
                    .map(AIdentifierExp::getName).collect(Collectors.toList());
        }

        if (knownComponentNames == null || knownComponentNames.isEmpty()) {
            throw new UnfoldException("No components found cannot fixed step with 0 components");
        }

        return knownComponentNames;
    }

    private void verifyArguments(List<PExp> formalArguments, ISimulationEnvironment env) throws UnfoldException {
        //maybe some of these tests are not necessary - but they are in my unit test
        if (formalArguments == null || formalArguments.size() != f1.getFormals().size()) {
            throw new UnfoldException("Invalid args");
        }
        if (env == null) {
            throw new UnfoldException("Simulation environment must not be null");
        }
    }

    @Override
    public boolean requireConfig() {
        return true;
    }

    @Override
    public IPluginConfiguration parseConfig(InputStream is) throws IOException {
        JsonNode root = new ObjectMapper().readTree(is);
        //We are only interested in one configuration, so in case it is an array we take the first one.
        if (root instanceof ArrayNode) {
            root = root.get(0);
        }
        root = root.get("configuration");
        JsonNode parameters = root.get("parameters");
        Config conf = null;
        try {
            conf = new Config(parameters);
        } catch (InvalidVariableStringException e) {
            e.printStackTrace();
        }
        return conf;
    }

    public static class Config implements IPluginConfiguration {

        private List<ModelParameter> modelParameters;

        public Config(JsonNode parameters) throws InvalidVariableStringException {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> result = mapper.convertValue(parameters, new TypeReference<Map<String, Object>>() {
            });
            modelParameters = buildParameters(result);
        }

        ;

        public List<ModelParameter> getModelParameters() {
            return modelParameters;
        }

        private List<ModelParameter> buildParameters(Map<String, Object> parameters) throws InvalidVariableStringException {
            List<ModelParameter> list = new Vector<>();

            if (parameters != null) {
                for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                    list.add(new ModelParameter(ModelConnection.Variable.parse(entry.getKey()), entry.getValue()));
                }
            }
            return list;
        }
    }
}





