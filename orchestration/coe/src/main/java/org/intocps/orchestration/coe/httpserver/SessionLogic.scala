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
package org.intocps.orchestration.coe.httpserver

import org.intocps.orchestration.coe.scala.Coe

/**
  * Created by ctha on 11-04-2016.
  */
case class SessionLogic(coe: Coe)
{

  type WsD = (String) => Unit

  val delegates: scala.collection.mutable.HashSet[WsD] = scala.collection.mutable.HashSet[WsD]()

  val proxyDelegate = (msg: String) =>
    {
      delegates.foreach(d =>
        {
          d(msg)
        })
    }

  def setWebSocket(socket: NanoWebSocketImpl): Unit =
  {
    this.coe.messageDelegate = proxyDelegate
    val d = (message: String) => if (socket != null && socket.isOpen)
      {
        socket.send(message)
      }
    this.delegates.add(d)
  }

  def removeSocket(): Unit =
  {
    this.coe.messageDelegate = null;
  }

  def containsSocket(): Boolean =
  {
    return !this.delegates.isEmpty
  }
}
