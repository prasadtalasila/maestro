package org.intocps.maestro.plugin.InitializerNew;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.intocps.maestro.ast.*;
import org.intocps.maestro.core.Framework;
import org.intocps.maestro.core.messages.IErrorReporter;
import org.intocps.maestro.plugin.IMaestroUnfoldPlugin;
import org.intocps.maestro.plugin.IPluginConfiguration;
import org.intocps.maestro.plugin.InitializerNew.ConversionUtilities.BooleanUtils;
import org.intocps.maestro.plugin.InitializerNew.ConversionUtilities.ImmutableMapper;
import org.intocps.maestro.plugin.InitializerNew.Spec.StatementContainer;
import org.intocps.maestro.plugin.UnfoldException;
import org.intocps.maestro.plugin.env.ISimulationEnvironment;
import org.intocps.maestro.plugin.env.UnitRelationship;
import org.intocps.maestro.plugin.env.fmi2.ComponentInfo;
import org.intocps.orchestration.coe.modeldefinition.ModelDescription;

import org.intocps.topologicalsorting.TarjanGraph;
import org.intocps.topologicalsorting.data.CyclicDependencyResult;
import org.intocps.topologicalsorting.data.DependencyResult;
import org.intocps.topologicalsorting.data.Edge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.collection.JavaConverters;

import javax.xml.xpath.XPathExpressionException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.intocps.maestro.ast.MableAstFactory.*;

public class InitializerNew implements IMaestroUnfoldPlugin {
    final static Logger logger = LoggerFactory.getLogger(InitializerNew.class);

    final AFunctionDeclaration f1 = MableAstFactory.newAFunctionDeclaration(new LexIdentifier("initialize", null),
            Arrays.asList(newAFormalParameter(newAArrayType(newANameType("FMI2Component")), newAIdentifier("component")),
                    newAFormalParameter(newAIntNumericPrimitiveType(), newAIdentifier("startTime")),
                    newAFormalParameter(newAIntNumericPrimitiveType(), newAIdentifier("endTime"))), MableAstFactory.newAVoidType());
    Config config;


    public InitializerNew() {
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

        StatementContainer.reset();

        PExp startTime = formalArguments.get(1).clone();
        PExp endTime = formalArguments.get(2).clone();
        var sc = StatementContainer.getInstance();
        sc.startTime = startTime;
        sc.endTime = endTime;

        //Setup experiment for all components
        knownComponentNames.forEach(comp -> {
            sc.createSetupExperimentStatement(comp.getText(), false, 0.0, true);
        });

        //Set variables for all components in IniPhase
        ManipulateComponentsVariables(env, knownComponentNames, sc, PhasePredicates.IniPhase(), false);

        //Enter initialization Mode
        knownComponentNames.forEach(comp -> {
            sc.enterInitializationMode(comp.getText());
        });

        //Set variables INPhase and IniePhase
        ManipulateComponentsVariables(env, knownComponentNames, sc, PhasePredicates.InPhase().or(PhasePredicates.IniePhase()), false);

        //Get variables INIThase
        ManipulateComponentsVariables(env, knownComponentNames, sc, PhasePredicates.InitPhase(), true);

        //Exit initialization Mode
        knownComponentNames.forEach(comp -> {
            sc.exitInitializationMode(comp.getText());
        });


        //All connection - Only relations in the fashion InputToOutput is necessary since the OutputToInputs are just a dublicate of this
        Set<UnitRelationship.Relation> relations =
                env.getRelations(knownComponentNames).stream().filter(o -> o.getDirection() == UnitRelationship.Relation.Direction.OutputToInput)
                        .collect(Collectors.toSet());


        //How to instantiate a generic class from scala
        var edges = relations.stream().map(o -> new Edge<UnitRelationship.Variable, UnitRelationship.Relation.InternalOrExternal>(o.getSource(),
                ImmutableMapper.convertSet(new HashSet<UnitRelationship.Variable>(o.getTargets().values())), o.getOrigin()))
                .collect(Collectors.toList());

        Iterable<Edge<UnitRelationship.Variable, UnitRelationship.Relation.InternalOrExternal>> iterable = edges;

        var graphSolver = new TarjanGraph(JavaConverters.iterableAsScalaIterableConverter(iterable).asScala());

        var topologicalOrderToInstantiate = graphSolver.topologicalSort();
        if(topologicalOrderToInstantiate instanceof CyclicDependencyResult){
            CyclicDependencyResult cycles = (CyclicDependencyResult) topologicalOrderToInstantiate;
            throw new UnfoldException("Cycles are present in the systems: " + cycles.cycle());
        }

        DependencyResult dependencyResult = (DependencyResult) topologicalOrderToInstantiate;

        
        /*
        Set<UnitRelationship.Relation> outputRelations =
                externalRelations.stream().filter(r -> r.getDirection() == UnitRelationship.Relation.Direction.OutputToInput)
                        .collect(Collectors.toSet());

        Set<UnitRelationship.Relation> inputRelations =
                externalRelations.stream().filter(r -> r.getDirection() == UnitRelationship.Relation.Direction.InputToOutput)
                        .collect(Collectors.toSet());

        Map<LexIdentifier, Map<ModelDescription.Types, List<ModelDescription.ScalarVariable>>> outputs =
                outputRelations.stream().map(r -> r.getSource().scalarVariable.instance).distinct().collect(Collectors.toMap(Function.identity(),
                        s -> outputRelations.stream().filter(r -> r.getSource().scalarVariable.instance.equals(s))
                                .map(r -> r.getSource().scalarVariable.getScalarVariable()).collect(Collectors.groupingBy(sv -> sv.getType().type))));

        Map<LexIdentifier, Map<ModelDescription.Types, List<ModelDescription.ScalarVariable>>> inputs =
                inputRelations.stream().map(r -> r.getSource().scalarVariable.instance).distinct().collect(Collectors.toMap(Function.identity(),
                        s -> inputRelations.stream().filter(r -> r.getSource().scalarVariable.instance.equals(s))
                                .map(r -> r.getSource().scalarVariable.getScalarVariable()).collect(Collectors.groupingBy(sv -> sv.getType().type))));


        Function<RelationVariable, String> getLogName = k -> k.instance.getText() + "." + k.getScalarVariable().getName();
*/

        /*
        Map<RelationVariable, PExp> csvFields =
                inputRelations.stream().map(r -> r.getTargets().values().stream().findFirst()).filter(Optional::isPresent).map(Optional::get)
                        .map(h -> h.scalarVariable).sorted(Comparator.comparing(getLogName::apply)).collect(Collectors.toMap(l -> l, r -> {

                    //the relation should be a one to one relation so just take the first one
                    RelationVariable fromVar = r;
                    PExp from = newAArrayIndexExp(
                            newAIdentifierExp(getBufferName(fromVar.instance, fromVar.getScalarVariable().type.type, UsageType.Out)), Collections
                                    .singletonList(newAIntLiteralExp(
                                            outputs.get(fromVar.instance).get(fromVar.getScalarVariable().getType().type).stream()
                                                    .map(ModelDescription.ScalarVariable::getName).collect(Collectors.toList())
                                                    .indexOf(fromVar.scalarVariable.getName()))));
                    return from;

                }, (oldValue, newValue) -> oldValue, LinkedHashMap::new));


        List<String> variableNames = csvFields.keySet().stream().map(getLogName).collect(Collectors.toList());
        try {



        } catch (XPathExpressionException e) {
            e.printStackTrace();
        }*/

        var statements = sc.getStatements();
        return newABlockStm(statements);
    }

    private void ManipulateComponentsVariables(ISimulationEnvironment env, List<LexIdentifier> knownComponentNames, StatementContainer sc,
            Predicate<ModelDescription.ScalarVariable> predicate, Boolean isGet) {
        knownComponentNames.forEach(comp -> {
            //Maybe start time and end Time could be passed to the class
            ComponentInfo info = env.getUnitInfo(comp, Framework.FMI2);
            try {
                var variablesToInitialize =
                        info.modelDescription.getScalarVariables().stream().filter(predicate).collect(Collectors.groupingBy(o -> o.getType().type));
                if (!variablesToInitialize.isEmpty()) {
                    variablesToInitialize.forEach((t, l) -> {
                        long[] scalarValueIndices = LongStream.range(0, l.size()).toArray();
                        if (isGet) {
                            switch (t) {
                                case Boolean:
                                    sc.getBooleans(comp.getText(), scalarValueIndices);
                                    break;
                                case Real:
                                    sc.getReals(comp.getText(), scalarValueIndices);
                                    break;
                                default:
                                    break;
                            }
                        } else {
                            switch (t) {
                                case Boolean: {
                                    boolean[] values =
                                            l.stream().map(o -> o.getType().start).map(Boolean.class::cast).collect(BooleanUtils.TO_BOOLEAN_ARRAY);
                                    sc.setBooleans(comp.getText(), scalarValueIndices, values);
                                }
                                break;
                                case Real:
                                    double[] values = l.stream().mapToDouble(o -> Double.parseDouble(o.getType().start.toString())).toArray();
                                    sc.setReals(comp.getText(), scalarValueIndices, values);
                                    break;
                                default:
                                    break;
                            }
                        }
                    });
                }
            } catch (XPathExpressionException | IllegalAccessException | InvocationTargetException e) {
                logger.error(e.getMessage());
            }
        });
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
        JsonNode configuration = root.get("configuration");
        JsonNode start_message = root.get("start_message");
        Config conf = new Config(configuration, start_message);
        return conf;
    }

    public static class Config implements IPluginConfiguration {

        public Config(JsonNode configuration, JsonNode start_message) {
            this.configuration = configuration;
            this.start_message = start_message;
        }

        private JsonNode configuration;

        private JsonNode start_message;

        public JsonNode getConfiguration() {
            return configuration;
        }

        public JsonNode getStart_message() {
            return start_message;
        }

    }


}



