package org.intocps.maestro.plugin.Initializer;

import org.intocps.maestro.framework.fmi2.Fmi2SimulationEnvironment;
import org.intocps.orchestration.coe.modeldefinition.ModelDescription;

import java.util.function.Predicate;

public class RelationsPredicates {
    public static Predicate<Fmi2SimulationEnvironment.Relation> external() {
        return o -> (o.getOrigin() == Fmi2SimulationEnvironment.Relation.InternalOrExternal.External);
    }

    public static Predicate<Fmi2SimulationEnvironment.Relation> internal() {
        return o -> (o.getOrigin() == Fmi2SimulationEnvironment.Relation.InternalOrExternal.Internal);
    }

    public static Predicate<Fmi2SimulationEnvironment.Relation> inputToOutput() {
        return o -> (o.getDirection() == Fmi2SimulationEnvironment.Relation.Direction.InputToOutput);
    }

    public static Predicate<Fmi2SimulationEnvironment.Relation> inputSource() {
        return o -> (o.getSource().scalarVariable.getScalarVariable().causality == ModelDescription.Causality.Input);
    }

    public static Predicate<Fmi2SimulationEnvironment.Relation> outputSource() {
        return o -> (o.getSource().scalarVariable.getScalarVariable().causality == ModelDescription.Causality.Output);
    }

}