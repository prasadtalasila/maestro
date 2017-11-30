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
package org.intocps.orchestration.coe.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.intocps.fmi.Fmi2Status;
import org.intocps.fmi.FmiInvalidNativeStateException;
import org.intocps.fmi.FmuInvocationException;
import org.intocps.fmi.FmuResult;
import org.intocps.fmi.IFmiComponent;
import org.intocps.fmi.InvalidParameterException;
import org.intocps.orchestration.coe.config.ModelConnection;
import org.intocps.orchestration.coe.modeldefinition.ModelDescription;
import org.intocps.orchestration.coe.modeldefinition.ModelDescription.ScalarVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Util
{
	final static Logger logger = LoggerFactory.getLogger(Util.class);

	public static FilenameFilter fmuFileFilter = new FilenameFilter()
	{

		@Override public boolean accept(File dir, String name)
		{
			return name.endsWith(".fmu");
		}
	};

	public static List<ModelConnection> parseConnections() throws Exception
	{
		List<ModelConnection> connections = new Vector<ModelConnection>();

		BufferedReader br = new BufferedReader(new FileReader(new File("src/test/resources/links.property".replace('/', File.separatorChar))));
		String line;
		while ((line = br.readLine()) != null)
		{
			if (line.trim().startsWith("//"))
				continue;
			connections.add(ModelConnection.parse(line));
		}
		br.close();

		return connections;
	}

	/**
	 * Method to set variables in an fmu instance
	 *
	 * @param comp
	 * @param type
	 * @param indices
	 * @param values
	 * @throws InvalidParameterException
	 * @throws FmiInvalidNativeStateException
	 */
	public static void setRaw(IFmiComponent comp, ModelDescription.Types type,
			Map<Long, Object> indexToValue)
			throws InvalidParameterException, FmiInvalidNativeStateException
	{
		long[] a = new long[indexToValue.size()];
		ArrayList<Object> values = new ArrayList<Object>(indexToValue.size());

		List<Long> indices = new Vector<Long>();
		indices.addAll(indexToValue.keySet());
		Collections.sort(indices);

		for (int i = 0; i < indices.size(); i++)
		{
			a[i] = indices.get(i);
			values.add(i, indexToValue.get(a[i]));
		}

		setRaw(comp, type, a, values);
	}

	private static void setRaw(IFmiComponent comp, ModelDescription.Types type,
			long[] indices, List<Object> values)
			throws InvalidParameterException, FmiInvalidNativeStateException
	{
		Fmi2Status status = Fmi2Status.Error;
		logger.trace("setRaw with comp: {}, type: {}, indices: {}, values: {}", comp, type, indices, values);
		switch (type)
		{
			case Boolean:
				status = comp.setBooleans(indices, ArrayUtils.toPrimitive((Boolean[]) values.toArray(new Boolean[] {})));
				break;
			case Integer:
			case Enumeration:
				status = comp.setIntegers(indices, ArrayUtils.toPrimitive((Integer[]) values.toArray(new Integer[] {})));
				break;
			case Real:
				status = comp.setReals(indices, ArrayUtils.toPrimitive((Double[]) values.toArray(new Double[] {})));
				break;
			case String:
				status = comp.setStrings(indices, values.toArray(new String[] {}));
				break;
			default:
				break;

		}

		logger.trace("setRaw complete. Type='" + type + "', indices {} values "
				+ values + " Status returned='" + status + "'", indices);

		if (!(status == Fmi2Status.OK || status==Fmi2Status.Warning))
		{
			logger.error("Error setting var of type='" + type
					+ "', indices {} values " + values + " Status returned='"
					+ status + "'", indices);
		}
	}

	/**
	 * MEthod to get variables in an fmu instance
	 *
	 * @param comp    the instance
	 * @param indices the indices to get
	 * @param type    the types
	 * @return a map from index to read value
	 * @throws FmuInvocationException
	 */
	public static Map<ScalarVariable, Object> getRaw(IFmiComponent comp,
			ScalarVariable[] indicesSv, long[] indices,
			ModelDescription.Types type) throws FmuInvocationException
	{
		if (indices.length <= 0)
		{
			return null;
		}
		FmuResult<?> res = null;
		Object[] resVal = null;

		logger.trace("getRaw {}, comp: {} indices: {}", type, comp, indices);
		switch (type)
		{
			case Boolean:
			{
				FmuResult<boolean[]> r = comp.getBooleans(indices);
				res = r;
				resVal = ArrayUtils.toObject(r.result);
				break;
			}
			case Integer:
			case Enumeration:
			{
				FmuResult<int[]> r = comp.getInteger(indices);
				res = r;
				resVal = ArrayUtils.toObject(r.result);
				break;
			}
			case Real:
			{
				FmuResult<double[]> r = comp.getReal(indices);
				res = r;
				resVal = ArrayUtils.toObject(r.result);
				break;
			}
			case String:
			{
				FmuResult<String[]> r = comp.getStrings(indices);
				res = r;
				resVal = r.result;
				break;
			}
			default:
				break;

		}
		logger.trace("getRaw {}, comp: {} indices: {} got values: {}", type, comp, indices, resVal);

		Map<ScalarVariable, Object> readVars = new HashMap<ScalarVariable, Object>();

		if (res != null && (res.status == Fmi2Status.OK|| res.status == Fmi2Status.Warning))
		{
			if(res.status==Fmi2Status.Warning)
			{
				logger.warn("received warning from getRaw {}, comp: {} indices: {} got values: {}", type, comp, indices, resVal);
			}
			for (int i = 0; i < indices.length; i++)
			{
				readVars.put(indicesSv[i], resVal[i]);
			}
		} else
		{
			return null;
		}

		return readVars;
	}

	public static String[] getArray(List<String> list)
	{
		return list.toArray(new String[list.size()]);
	}
}
