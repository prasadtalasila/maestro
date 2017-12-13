/*
* This file is part of the INTO-CPS toolchain.
*
* Copyright (c) 2017-CurrentYear, INTO-CPS Association,
* c/o Professor Peter Gorm Larsen, Department of Engineering
* Finlandsgade 22, 8200 Aarhus N.
*
* All rights reserved.
*
* THIS PROGRAM IS PROVIDED UNDER THE TERMS OF GPL VERSION 3 LICENSE OR
* THIS INTO-CPS ASSOCIATION PUBLIC LICENSE VERSION 1.0.
* ANY USE, REPRODUCTION OR DISTRIBUTION OF THIS PROGRAM CONSTITUTES
* RECIPIENT'S ACCEPTANCE OF THE OSMC PUBLIC LICENSE OR THE GPL 
* VERSION 3, ACCORDING TO RECIPIENTS CHOICE.
*
* The INTO-CPS toolchain  and the INTO-CPS Association Public License 
* are obtained from the INTO-CPS Association, either from the above address,
* from the URLs: http://www.into-cps.org, and in the INTO-CPS toolchain distribution.
* GNU version 3 is obtained from: http://www.gnu.org/copyleft/gpl.html.
*
* This program is distributed WITHOUT ANY WARRANTY; without
* even the implied warranty of  MERCHANTABILITY or FITNESS FOR
* A PARTICULAR PURPOSE, EXCEPT AS EXPRESSLY SET FORTH IN THE
* BY RECIPIENT SELECTED SUBSIDIARY LICENSE CONDITIONS OF
* THE INTO-CPS ASSOCIATION.
*
* See the full INTO-CPS Association Public License conditions for more details.
*/

/*
* Author:
*		Kenneth Lausdahl
*		Casper Thule
*/
package org.intocps.orchestration.coe.cosim

import org.intocps.orchestration.coe.json.InitializationMsgJson

import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.collection.JavaConverters.mapAsScalaMapConverter
import org.intocps.orchestration.coe.config.ModelConnection
import org.intocps.orchestration.coe.config.ModelConnection.Variable
import org.intocps.orchestration.coe.scala.CoeObject
import org.slf4j.LoggerFactory
import org.intocps.orchestration.coe.cosim.varstep.StepsizeCalculator
import InitializationMsgJson.Constraint
import org.intocps.orchestration.coe.cosim.varstep.StepsizeInterval
import org.intocps.orchestration.coe.scala.VariableResolver

import scala.collection.JavaConversions._
import org.intocps.orchestration.coe.scala.CoeObject.FmiInstanceConfigScalaWrapper
import org.intocps.orchestration.coe.cosim.varstep.StepsizeCalculator
import org.intocps.orchestration.coe.cosim.base.FmiSimulationInstance
import org.intocps.orchestration.coe.cosim.varstep.StepsizeCalculator
import InitializationMsgJson.Constraint
import org.intocps.fmi.Fmi2Status

class VariableStepSizeCalculator(constraints: java.util.Set[Constraint],
                                 val stepsizeInterval: StepsizeInterval,
                                 initialStepsize: java.lang.Double) extends CoSimStepSizeCalculator
{

  var calc: StepsizeCalculator = null
  var supportsRollback: Boolean = true

  val logger = LoggerFactory.getLogger(this.getClass)

  var instances: Map[ModelConnection.ModelInstance, FmiSimulationInstance] = null

  var lastStepsize: Double = -1

  def initialize(instances: Map[ModelConnection.ModelInstance, FmiSimulationInstance], outputs: CoeObject.Outputs, inputs: CoeObject.Inputs) =
  {
    logger.trace("Initializing the variable step size calculator")

    supportsRollback = instances.forall(x => x match
    {
      case (mi, instance) => instance.config.asInstanceOf[FmiInstanceConfigScalaWrapper].canGetSetState
    })
     calc = new StepsizeCalculator(constraints, stepsizeInterval, initialStepsize, instances.asJava);

    this.instances = instances
  }

  def getStepSize(currentTime: Double, state: CoeObject.GlobalState): Double =
  {

    logger.trace("Calculating the stepsize for the variable step size calculator")

    val data = state.instanceStates.map(s => s._1 -> s._2.state.asJava)
    val der = state.instanceStates.map(s => s._1 ->
      {
        if (s._2.derivatives != null)
          {
            s._2.derivatives.map(derMap => derMap._1 -> derMap._2.map(orderValMap => java.lang.Integer.valueOf(orderValMap._1) -> java.lang.Double.valueOf(orderValMap._2)).asJava).asJava
          } else
          {
            null
          }
      })

    val minStepsize = stepsizeInterval.getMinimalStepsize
    val maxStepsize = stepsizeInterval.getMaximalStepsize

    val maxStepSizes = instances.map
    { case (mi, x) =>
      val result = x.instance.getMaxStepSize
      if (result.status != Fmi2Status.OK)
        {
          logger.debug("GetMaxStepSize failed for the FMU: " + mi + ". The return was " + result.status)
        } else
        {
          if (result.result < minStepsize)
            {
              val diff =new java.lang.Double(minStepsize-result.result)
              logger.warn("'{}'.GetMaxStepSize = {} is smaller than minimum {} diff {}",Array( mi, result.result,minStepsize,diff):_*)
            } else
            {
              logger.trace("'{}'.GetMaxStepSize = {}", mi, result.result: Any)
            }
        }

      result;
    }.filter(r => r.status == Fmi2Status.OK && r.result >= minStepsize && r.result <= maxStepsize).map(r=>r.result)

    val maxStepSize: Double = if (maxStepSizes.isEmpty)
      {
        maxStepsize
      } else
      {
        maxStepSizes.min
      }

    logger.trace("Max FMU step size used by step-size calculator: {}", maxStepSize)

    lastStepsize = calc.getStepsize(currentTime, data.asJava, if (der != null)
      {
        der.asJava
      } else
      {
        null
      }, maxStepSize)


    logger.trace("Calculated step size: {}", lastStepsize)
    return lastStepsize
  }

  def getLastStepsize(): Double =
  {
    lastStepsize
  }

  def getObservableOutputs(variableResolver: VariableResolver): CoeObject.Outputs =
  {

    //TODO rewrite this into a function and reuse in the coe for get output and input
    val tmp = constraints.toSet[Constraint].map
    { x => x.getPorts.toSet[Variable].map
    { v => variableResolver.resolve(v) }
    }
    val t2 = tmp.flatten
    val t22 = t2.map(f => f._1)
    val t222 = t22.map
    { x => x -> t2.filter(p => p._1 == x).map(f => f._2) }
    val t = t222.toMap

    t
  }

  def validateStep(nextTime: Double, newState: CoeObject.GlobalState): StepValidationResult =
  {
    val state = newState.instanceStates.map(s => s._1 -> s._2.state.asJava);
    val r = calc.validateStep(nextTime, state.asJava, supportsRollback)
    return new StepValidationResult(r.isValid(), r.hasReducedStepsize(), r.getStepsize)
  }

  def setEndTime(endTime: Double) =
  {
    calc.setEndTime(endTime)
  }

}