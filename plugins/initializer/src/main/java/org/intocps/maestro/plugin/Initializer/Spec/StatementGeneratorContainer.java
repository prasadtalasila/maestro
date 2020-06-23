package org.intocps.maestro.plugin.Initializer.Spec;

import org.apache.commons.lang3.tuple.Pair;
import org.intocps.maestro.ast.*;
import org.intocps.orchestration.coe.config.ModelConnection;
import org.intocps.orchestration.coe.modeldefinition.ModelDescription;

import java.util.*;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import static org.intocps.maestro.ast.MableAstFactory.*;

public class StatementGeneratorContainer {
    private static final Function<String, LexIdentifier> createLexIdentifier = s -> new LexIdentifier(s.replace("-", ""), null);
    private static StatementGeneratorContainer container = null;
    private final LexIdentifier statusVariable = createLexIdentifier.apply("status");
    private final List<PStm> statements = new ArrayList<>();
    private final Map<Integer, LexIdentifier> realArrays = new HashMap<>();
    private final Map<Integer, LexIdentifier> boolArrays = new HashMap<>();
    private final Map<Integer, LexIdentifier> longArrays = new HashMap<>();
    private final Map<Integer, LexIdentifier> intArrays = new HashMap<>();
    private final Map<Integer, LexIdentifier> stringArrays = new HashMap<>();
    private final EnumMap<ModelDescription.Types, String> typesStringMap = new EnumMap<ModelDescription.Types, String>(ModelDescription.Types.class){
        {
            put(ModelDescription.Types.Integer, "Integer");
            put(ModelDescription.Types.String, "String");
            put(ModelDescription.Types.Boolean, "Boolean");
            put(ModelDescription.Types.Real, "Real");
        }
    };


    private final Map<String, Map<Long, VariableLocation>> instanceVariables = new HashMap<>();
    private final Map<ModelConnection.ModelInstance, Map<ModelDescription.ScalarVariable, AbstractMap.SimpleEntry<ModelConnection.ModelInstance, ModelDescription.ScalarVariable>>>
            inputToOutputMapping = new HashMap<>();
    private final IntFunction<String> booleanArrayVariableName = i -> "booleanValueSize" + i;
    private final IntFunction<String> realArrayVariableName = i -> "realValueSize" + i;
    private final IntFunction<String> intArrayVariableName = i -> "intValueSize" + i;
    private final IntFunction<String> stringArrayVariableName = i -> "stringValueSize" + i;

    public PExp startTime;
    public PExp endTime;
    /**
     * <code>instancesLookupDependencies</code> is set to true the co-simulatino enters the stage where
     * dependencies are to be looked up. It is detected by the first "get".
     */
    private boolean instancesLookupDependencies = false;


    private StatementGeneratorContainer() {
        AVariableDeclaration status =
                newAVariableDeclaration(statusVariable, newAIntNumericPrimitiveType(), newAExpInitializer(newAIntLiteralExp(0)));
        statements.add(newALocalVariableStm(status));
    }

    public static StatementGeneratorContainer getInstance() {
        if (container == null) {
            container = new StatementGeneratorContainer();
        }
        return container;
    }

    private static PStm createGetSVsStatement(String instanceName, String functionName, long[] longs, LexIdentifier valueArray,
            LexIdentifier valRefArray, LexIdentifier statusVariable) {
        return newAAssignmentStm(newAIdentifierStateDesignator(statusVariable), newADotExp(newAIdentifierExp(createLexIdentifier.apply(instanceName)),
                newACallExp(newAIdentifierExp(createLexIdentifier.apply(functionName)), new ArrayList<PExp>(
                        Arrays.asList(newAIdentifierExp(valRefArray), newAUIntLiteralExp((long) longs.length), newAIdentifierExp(valueArray))))));
    }

    public static void reset() {
        container = null;
    }

    public SPrimitiveTypeBase FMITypeToMablType(ModelDescription.Types type) {
        switch (type) {
            case Boolean:
                return newABoleanPrimitiveType();
            case Real:
                return newARealNumericPrimitiveType();
            case Integer:
                return newAIntNumericPrimitiveType();
            case String:
                return newAStringPrimitiveType();
            default:
                throw new UnsupportedOperationException("Converting fmi type: " + type + " to mabl type is not supported.");
        }
    }

    public List<PStm> getStatements() {
        return statements;
    }

    public void createSetupExperimentStatement(String instanceName, boolean toleranceDefined, double tolerance, boolean stopTimeDefined) {
        PStm statement = newAAssignmentStm(newAIdentifierStateDesignator(statusVariable),
                newADotExp(newAIdentifierExp(createLexIdentifier.apply(instanceName)),
                        newACallExp(newAIdentifierExp(createLexIdentifier.apply("setupExperiment")), new ArrayList<PExp>(
                                Arrays.asList(newABoolLiteralExp(toleranceDefined), newARealLiteralExp(tolerance), this.startTime.clone(),
                                        newABoolLiteralExp(stopTimeDefined), this.endTime.clone())))

                ));
        statements.add(statement);
    }

    public void enterInitializationMode(String instanceName) {
        PStm statement = newAAssignmentStm(newAIdentifierStateDesignator(statusVariable),
                newADotExp(newAIdentifierExp(createLexIdentifier.apply(instanceName)),
                        newACallExp(newAIdentifierExp(createLexIdentifier.apply("enterInitializationMode")), null)));
        statements.add(statement);
    }

    public void setReals(String instanceName, long[] longs, double[] doubles) {
        // Create a valueLocator to locate the value corresponding to a given valuereference.
        IntFunction<Pair<PExp, List<PStm>>> valueLocator =
                generateInstanceVariablesValueLocator(instanceName, longs, i -> MableAstFactory.newARealLiteralExp(doubles[i]),
                        ModelDescription.Types.Real);

        // Create the array to contain the values
        LexIdentifier valueArray = findArrayOfSize(realArrays, longs.length);
        // The array does not exist. Create it and initialize it.
        if (valueArray == null) {
            valueArray =
                    createArray(longs, doubles.length, valueLocator, realArrayVariableName.apply(longs.length), ModelDescription.Types.Real, realArrays);
        } else {
            // The array exists. Assign its values.
            assignValueToArray(doubles.length, valueLocator, valueArray);
        }

        LexIdentifier valRefs = findOrCreateValueReferenceArrayAndAssign(longs);

        generateAssignmentStm(instanceName, longs, valueArray, valRefs, "setReal");
    }

    public void setBooleans(String instanceName, long[] longs, boolean[] booleans) {
        // Create a valueLocator to locate the value corresponding to a given valuereference.
        IntFunction<Pair<PExp, List<PStm>>> valueLocator =
                generateInstanceVariablesValueLocator(instanceName, longs, i -> newABoolLiteralExp(booleans[i]), ModelDescription.Types.Boolean);

        // Create the array to contain the values
        LexIdentifier valueArray = findArrayOfSize(boolArrays, booleans.length);
        // The array does not exist. Create it and initialize it.
        if (valueArray == null) {
            valueArray =
                    createArray(longs, booleans.length, valueLocator, booleanArrayVariableName.apply(longs.length), ModelDescription.Types.Boolean,
                            boolArrays);
        } else {
            // The array exists. Assign its values.
            assignValueToArray(booleans.length, valueLocator, valueArray);
        }

        LexIdentifier valRefs = findOrCreateValueReferenceArrayAndAssign(longs);

        generateAssignmentStm(instanceName, longs, valueArray, valRefs, "setBoolean");
    }


    public void setIntegers(String instanceName, long[] longs, int[] ints) {
        // Create a valueLocator to locate the value corresponding to a given valuereference.
        IntFunction<Pair<PExp, List<PStm>>> valueLocator =
                generateInstanceVariablesValueLocator(instanceName, longs, i -> MableAstFactory.newAIntLiteralExp(ints[i]),
                        ModelDescription.Types.Integer);

        // Create the array to contain the values
        LexIdentifier valueArray = findArrayOfSize(intArrays, longs.length);
        // The array does not exist. Create it and initialize it.
        if (valueArray == null) {
            valueArray =
                    createArray(longs, ints.length, valueLocator, intArrayVariableName.apply(longs.length), ModelDescription.Types.Integer, intArrays);
        } else {
            // The array exists. Assign its values.
            assignValueToArray(ints.length, valueLocator, valueArray);
        }

        LexIdentifier valRefs = findOrCreateValueReferenceArrayAndAssign(longs);

        generateAssignmentStm(instanceName, longs, valueArray, valRefs, "setInteger");
    }

    public void setStrings(String instanceName, long[] longs, String[] strings) {
        // Create a valueLocator to locate the value corresponding to a given valuereference.
        IntFunction<Pair<PExp, List<PStm>>> valueLocator =
                generateInstanceVariablesValueLocator(instanceName, longs, i -> newAStringLiteralExp(strings[i]), ModelDescription.Types.String);

        // Create the array to contain the values
        LexIdentifier valueArray = findArrayOfSize(stringArrays, strings.length);
        // The array does not exist. Create it and initialize it.
        if (valueArray == null) {
            valueArray = createArray(longs, strings.length, valueLocator, stringArrayVariableName.apply(longs.length), ModelDescription.Types.String,
                    stringArrays);
        } else {
            // The array exists. Assign its values.
            assignValueToArray(strings.length, valueLocator, valueArray);
        }

        LexIdentifier valRefs = findOrCreateValueReferenceArrayAndAssign(longs);

        generateAssignmentStm(instanceName, longs, valueArray, valRefs, "setString");
    }

    private void generateAssignmentStm(String instanceName, long[] longs, LexIdentifier valueArray, LexIdentifier valRefs, String setCommand) {
        PStm statement = MableAstFactory.newAAssignmentStm(MableAstFactory.newAIdentifierStateDesignator(statusVariable), MableAstFactory
                .newADotExp(MableAstFactory.newAIdentifierExp(createLexIdentifier.apply(instanceName)), MableAstFactory
                        .newACallExp(MableAstFactory.newAIdentifierExp(createLexIdentifier.apply(setCommand)), new ArrayList<PExp>(
                                Arrays.asList(MableAstFactory.newAIdentifierExp(valRefs), MableAstFactory.newAUIntLiteralExp((long) longs.length),
                                        MableAstFactory.newAIdentifierExp(valueArray))))

                ));
        statements.add(statement);
    }

    private LexIdentifier createArray(long[] longs, int valueLength, IntFunction<Pair<PExp, List<PStm>>> valueLocator, String arrayName,
            ModelDescription.Types type, Map<Integer, LexIdentifier> array) {
        LexIdentifier valueArray;
        List<PExp> args = new ArrayList<>();
        for (int i = 0; i < valueLength; i++) {
            Pair<PExp, List<PStm>> value = valueLocator.apply(i);
            args.add(value.getLeft());
            if (value.getRight() != null) {
                statements.addAll(value.getRight());
            }
        }

        PInitializer initializer = MableAstFactory.newAArrayInitializer(args);
        valueArray = createNewArrayAndAddToStm(arrayName, array, MableAstFactory.newAArrayType(FMITypeToMablType(type), valueLength), initializer);
        return valueArray;
    }


    private void assignValueToArray(int valueLength, IntFunction<Pair<PExp, List<PStm>>> valueLocator, LexIdentifier valueArray) {
        for (int i = 0; i < valueLength; i++) {
            Pair<PExp, List<PStm>> value = valueLocator.apply(i);
            if (value.getRight() != null) {
                statements.addAll(value.getRight());
            }
            PStm stm = newAAssignmentStm(newAArayStateDesignator(newAIdentifierStateDesignator(valueArray), newAIntLiteralExp(i)), value.getLeft());
            statements.add(stm);
        }
    }


    public void setInputOutputMapping(
            Map<ModelConnection.ModelInstance, Map<ModelDescription.ScalarVariable, AbstractMap.SimpleEntry<ModelConnection.ModelInstance, ModelDescription.ScalarVariable>>> inputOutputMapping) {
        inputOutputMapping.forEach(this.inputToOutputMapping::put);
    }

    private LexIdentifier findOrCreateValueReferenceArrayAndAssign(long[] valRefs) {
        LexIdentifier arrayName = findArrayOfSize(longArrays, valRefs.length);
        if (arrayName != null) {

            for (int i = 0; i < valRefs.length; i++) {
                PStm stm = newAAssignmentStm(newAArayStateDesignator(newAIdentifierStateDesignator(arrayName), newAIntLiteralExp(i)),
                        newAUIntLiteralExp(valRefs[i]));
                statements.add(stm);
            }
            return arrayName;
        } else {
            return createNewArrayAndAddToStm("valRefsSize" + valRefs.length, longArrays,
                    newAArrayType(newAUIntNumericPrimitiveType(), valRefs.length),
                    newAArrayInitializer(Arrays.stream(valRefs).mapToObj(valRef -> newAUIntLiteralExp(valRef)).collect(Collectors.toList())));
        }
    }

    private LexIdentifier findArrayOfSize(Map<Integer, LexIdentifier> arrays, int i) {
        return arrays.getOrDefault(i, null);
    }

    private LexIdentifier createNewArrayAndAddToStm(String name, Map<Integer, LexIdentifier> array, AArrayType arType, PInitializer initializer) {
        LexIdentifier lexID = createLexIdentifier.apply(name);
        PStm stm = newALocalVariableStm(newAVariableDeclaration(lexID, arType, initializer));
        statements.add(stm);
        array.put(arType.getSize(), lexID);
        return lexID;
    }

    public void getBooleans(String instanceName, long[] longs) {
        if (!instancesLookupDependencies) {
            instancesLookupDependencies = true;
        }

        // Create the value array
        LexIdentifier valueArray = findArrayOfSize(boolArrays, longs.length);
        // The array does not exist. Create it
        if (valueArray == null) {
            valueArray = createNewArrayAndAddToStm(booleanArrayVariableName.apply(longs.length), boolArrays,
                    newAArrayType(newABoleanPrimitiveType(), longs.length), null);
        }

        // Create the valRefArray
        LexIdentifier valRefArray = findOrCreateValueReferenceArrayAndAssign(longs);

        // Create the getBoolean statement
        PStm statement = createGetSVsStatement(instanceName, "getBoolean", longs, valueArray, valRefArray, statusVariable);
        statements.add(statement);

        // Update instanceVariables
        updateInstanceVariables(instanceName, longs, valueArray, ModelDescription.Types.Boolean);
    }

    public void getReals(String instanceName, long[] longs) {
        if (!instancesLookupDependencies) {
            instancesLookupDependencies = true;
        }

        // Create the value array
        LexIdentifier valueArray = findArrayOfSize(realArrays, longs.length);
        // The array does not exist. Create it
        if (valueArray == null) {
            valueArray =
                    createNewArrayAndAddToStm("realValueSize" + longs.length, realArrays, newAArrayType(newARealNumericPrimitiveType(), longs.length),
                            null);
        }

        // Create the valRefArray
        LexIdentifier valRefArray = findOrCreateValueReferenceArrayAndAssign(longs);

        // Create the statement
        PStm statement = createGetSVsStatement(instanceName, "getReal", longs, valueArray, valRefArray, statusVariable);
        statements.add(statement);

        // Update instanceVariables
        updateInstanceVariables(instanceName, longs, valueArray, ModelDescription.Types.Real);
    }

    public void getIntegers(String instanceName, long[] longs) {
        if (!instancesLookupDependencies) {
            instancesLookupDependencies = true;
        }

        // Create the value array
        LexIdentifier valueArray = findArrayOfSize(intArrays, longs.length);
        // The array does not exist. Create it
        if (valueArray == null) {
            valueArray =
                    createNewArrayAndAddToStm("intValueSize" + longs.length, intArrays, newAArrayType(newARealNumericPrimitiveType(), longs.length),
                            null);
        }

        // Create the valRefArray
        LexIdentifier valRefArray = findOrCreateValueReferenceArrayAndAssign(longs);

        // Create the statement
        PStm statement = createGetSVsStatement(instanceName, "getInteger", longs, valueArray, valRefArray, statusVariable);
        statements.add(statement);

        // Update instanceVariables
        updateInstanceVariables(instanceName, longs, valueArray, ModelDescription.Types.Integer);
    }

    public void getStrings(String instanceName, long[] longs) {
        if (!instancesLookupDependencies) {
            instancesLookupDependencies = true;
        }

        // Create the value array
        LexIdentifier valueArray = findArrayOfSize(stringArrays, longs.length);
        // The array does not exist. Create it
        if (valueArray == null) {
            valueArray = createNewArrayAndAddToStm("stringValueSize" + longs.length, intArrays,
                    newAArrayType(newARealNumericPrimitiveType(), longs.length), null);
        }

        // Create the valRefArray
        LexIdentifier valRefArray = findOrCreateValueReferenceArrayAndAssign(longs);

        // Create the statement
        PStm statement = createGetSVsStatement(instanceName, "getString", longs, valueArray, valRefArray, statusVariable);
        statements.add(statement);

        // Update instanceVariables
        updateInstanceVariables(instanceName, longs, valueArray, ModelDescription.Types.String);
    }


    private void updateInstanceVariables(String instanceName, long[] longs, LexIdentifier valueArray, ModelDescription.Types fmiType) {
        Map<Long, VariableLocation> instanceVariables = this.instanceVariables.computeIfAbsent(instanceName, k -> new HashMap<>());

        // Move the retrieved values to the respective instance variables
        for (int i = 0; i < longs.length; i++) {
            VariableLocation svVar = instanceVariables.get(longs[i]);
            AArrayIndexExp assignmentExpression =
                    newAArrayIndexExp(newAIdentifierExp(valueArray), Collections.singletonList(newAUIntLiteralExp((long) i)));

            // Create the variable, initialize it, and add it to instanceVariables
            if (svVar == null) {

                String id = instanceName + "SvValRef" + longs[i];
                PStm stm = newALocalVariableStm(
                        newAVariableDeclaration(createLexIdentifier.apply(id), FMITypeToMablType(fmiType),
                                newAExpInitializer(assignmentExpression)));
                statements.add(stm);

                VariableLocation varLoc = new VariableLocation(id, fmiType);
                instanceVariables.put(longs[i], varLoc);
            }
            // Assign to the variable
            else {
                PStm stm = newAAssignmentStm(newAIdentifierStateDesignator(createLexIdentifier.apply(svVar.variableId)), assignmentExpression);
                statements.add(stm);
            }
        }
    }


    /**
     * This creates a function (<code>valueLocator</code>) that is used to locate an output corresponding to the given input that is to be set.
     * If the co-simulation has entered <code>instancesLookupDependencies</code> then it will determine the variable using the inputs to outputs map (<code>inputOutputMapping2</code>)
     * and locate it using <code>instanceVariables</code>.
     * Otherwise it uses the argument <code>literalExp</code> to return a <code>valueLocator</code>.
     * It uses  to
     *
     * @param instanceName name of the instance on which the FMI-function is invoked.
     * @param valRefs      value reference argument to the FMI function
     * @param literalExp   Function to apply the int to, if the co-simulation has not entered <code>instancesLookupDependencies</code> mode.
     * @return a function (valueLocator) that, given an int representing the index of <code>valRefs</code>, will locate the corresponding output
     */
    public IntFunction<Pair<PExp, List<PStm>>> generateInstanceVariablesValueLocator(String instanceName, long[] valRefs,
            IntFunction<PExp> literalExp, ModelDescription.Types targetType) {
        IntFunction<Pair<PExp, List<PStm>>> valueLocator = null;

        if (this.instancesLookupDependencies) {
            Optional<ModelConnection.ModelInstance> key =
                    this.inputToOutputMapping.keySet().stream().filter(x -> x.instanceName.equals(instanceName)).findFirst();

            if (key.isPresent()) {
                Map<ModelDescription.ScalarVariable, AbstractMap.SimpleEntry<ModelConnection.ModelInstance, ModelDescription.ScalarVariable>>
                        svInputsToOutputs = this.inputToOutputMapping.get(key.get());
                valueLocator = i -> {
                    Optional<ModelDescription.ScalarVariable> svInputVarToOutput =
                            svInputsToOutputs.keySet().stream().filter(x -> x.valueReference == valRefs[i]).findFirst();

                    if (svInputVarToOutput.isPresent()) {
                        AbstractMap.SimpleEntry<ModelConnection.ModelInstance, ModelDescription.ScalarVariable> output =
                                svInputsToOutputs.get(svInputVarToOutput.get());
                        VariableLocation variable = this.instanceVariables.get(output.getKey().instanceName).get(output.getValue().valueReference);
                        LexIdentifier variableLexId = createLexIdentifier.apply(variable.variableId);
                        // The type of the located variable might not be the correct type.
                        List<PStm> statements = null;
                        if (output.getValue().type.type != targetType) {
                            statements = new ArrayList<>();
                            String name = null;
                            if (variable.typeMapping.containsKey(targetType)) {
                                // The variable exists. Assign a new value to it.
                                name = variable.typeMapping.get(targetType);
                            } else {
                                // The variable does not exist. Create it.
                                name = instanceName + "SvValRef" + valRefs[i] + targetType;
                                variable.typeMapping.put(targetType, name);
                                ALocalVariableStm stm =
                                        newALocalVariableStm(newAVariableDeclaration(new LexIdentifier(name, null), FMITypeToMablType(targetType)));
                                statements.add(stm);
                            }

                            // Convert the value
                            statements.add(newExternalStm(newACallExp(newAIdentifierExp(new LexIdentifier("convert" + typesStringMap.get(output.getValue().type.type) + "2"+  typesStringMap.get(targetType),
                                            null)),
                                    new ArrayList<PExp>(List.of(newAIdentifierExp(variable.variableId), newAIdentifierExp(name))))));

                            variableLexId = createLexIdentifier.apply(name);
                        }
                        return Pair.of(newAIdentifierExp(variableLexId), statements);
                    } else {
                        return Pair.of(newAIdentifierExp(createLexIdentifier
                                .apply("Failed to find the variable with valref " + valRefs[i] + " for instance: " + key.get().instanceName)), null);
                    }
                };
            }
        } else {
            valueLocator = i -> Pair.of(literalExp.apply(i), null);
        }
        return valueLocator;
    }

    public void exitInitializationMode(String instanceName) {
        PStm statement = newAAssignmentStm(newAIdentifierStateDesignator(statusVariable),
                newADotExp(newAIdentifierExp(createLexIdentifier.apply(instanceName)),
                        newACallExp(newAIdentifierExp(createLexIdentifier.apply("exitInitializationMode")), null)));
        statements.add(statement);
    }
}