/*
 * Copyright (C) 2013
 * University of Massachusetts
 * All Rights Reserved 
 */
package edu.umass.cs.gns.localnameserver;

import edu.umass.cs.gns.clientsupport.Defs;
import edu.umass.cs.gns.clientsupport.Intercessor;
import edu.umass.cs.gns.main.GNS;
import edu.umass.cs.gns.nsdesign.GNSNodeConfig;
import edu.umass.cs.gns.nsdesign.packet.CommandPacket;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.UnknownHostException;

/**
 * Handles sending and receiving of commands.
 *
 * @author westy
 */
public class CommandRequest {

  public static void handlePacketCommandRequest(JSONObject incomingJSON) throws JSONException, UnknownHostException {

    CommandPacket packet = new CommandPacket(incomingJSON);
    if (packet.getReturnValue() == null) {
      // PACKET IS GOING OUT TO A NAME SERVER
      // If the command is a guid or name command (has a GUID or NAME field) we MUST send it to a server 
      // that is the active server for that GUID
      // because the code at the name server assumes it can look up the info for that record locally on the server.
      // We pick a name server based on that record name using the active name servers info in the cache.
      int serverID = pickNameServer(getUsefulRecordName(packet.getCommand()));
      GNS.getLogger().info("LNS" + LocalNameServer.getNodeID() + " transmitting CommandPacket " + incomingJSON + " to " + serverID);
      LocalNameServer.sendToNS(incomingJSON, serverID);
    } else {
      // PACKET IS COMING BACK FROM A NAMESERVER
      Intercessor.handleIncomingPackets(incomingJSON);
    }
  }

  /**
   * Look up the name or guid in the command 
   * @param incomingJSON
   * @return 
   */
  private static String getUsefulRecordName(JSONObject incomingJSON) {
    // try a couple
    try {
      return incomingJSON.getString(Defs.GUID);
    } catch (JSONException e) {
      try {
        return incomingJSON.getString(Defs.NAME);
      } catch (JSONException f) {
        return null;
      }
    }
  }

  /**
   * Picks a name server based on the guid.
   *
   * @param guid
   * @return
   */
  private static int pickNameServer(String guid) {
    if (guid != null) {
      CacheEntry cacheEntry = LocalNameServer.getCacheEntry(guid);
      // PRoBABLY WILL NEED SOMETHING IN HERE TO FORCE IT TO UPDATE THE ActiveNameServers
      if (cacheEntry != null && cacheEntry.getActiveNameServers() != null && !cacheEntry.getActiveNameServers().isEmpty()) {
        int id = LocalNameServer.getGnsNodeConfig().getClosestServer(cacheEntry.getActiveNameServers());
        if (id != GNSNodeConfig.INVALID_NAME_SERVER_ID) {
          return id;
        }
      }
    }
    return LocalNameServer.getGnsNodeConfig().getClosestServer(LocalNameServer.getGnsNodeConfig().getNameServerIDs());
  }
}
