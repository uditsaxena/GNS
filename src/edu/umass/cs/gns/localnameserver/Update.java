package edu.umass.cs.gns.localnameserver;

import edu.umass.cs.gns.main.GNS;
import edu.umass.cs.gns.main.StartLocalNameServer;
import edu.umass.cs.gns.packet.ConfirmUpdateLNSPacket;
import edu.umass.cs.gns.packet.Transport;
import edu.umass.cs.gns.packet.UpdateAddressPacket;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;

public class Update {

  public static void handlePacketUpdateAddressLNS(JSONObject json)
          throws JSONException, UnknownHostException {


    UpdateAddressPacket updateAddressPacket = new UpdateAddressPacket(json);

    GNS.getLogger().fine(" UPDATE ADDRESS PACKET RECEIVED. Operation: " + updateAddressPacket.getOperation());

    if (updateAddressPacket.getOperation().isUpsert()) {
      AddRemove.handleUpsert(updateAddressPacket, InetAddress.getByName(Transport.getReturnAddress(json)), Transport.getReturnPort(json));
    } else {
      LocalNameServer.incrementUpdateRequest(updateAddressPacket.getName());

      InetAddress senderAddress = null;
      int senderPort = -1;
      senderPort = Transport.getReturnPort(json);
      if (Transport.getReturnAddress(json) != null) {
        senderAddress = InetAddress.getByName(Transport.getReturnAddress(json));
      }
      SendUpdatesTask updateTask = new SendUpdatesTask(updateAddressPacket,
              senderAddress, senderPort, System.currentTimeMillis(), new HashSet<Integer>());
      LocalNameServer.timer.schedule(updateTask, 0, StartLocalNameServer.queryTimeout);
    }
  }
  static int numUpdateResponse = 0;

  public static void handlePacketConfirmUpdateLNS(JSONObject json) throws UnknownHostException, JSONException {
    ConfirmUpdateLNSPacket confirmPkt = new ConfirmUpdateLNSPacket(json);
    numUpdateResponse++;

    if (StartLocalNameServer.debugMode) {
      GNS.getLogger().fine("ListenerAddressUpdateConfirmation: Received ResponseNum: "
              + (numUpdateResponse) + " --> " + confirmPkt.toString());
    }

    if (confirmPkt.isSuccess()) {

      UpdateInfo updateInfo = LocalNameServer.removeUpdateInfo(confirmPkt.getLNSRequestID());
      if (updateInfo == null) {
        if (StartLocalNameServer.debugMode) {
          GNS.getLogger().fine("Update confirm return info not found.");
        }
      } else {
        // send the confirmation back to the originator of the update
        if (StartLocalNameServer.debugMode) {
          GNS.getLogger().severe("LNSListenerUpdate CONFIRM (ns " + LocalNameServer.nodeID + ") to "
                  + updateInfo.senderAddress + ":" + updateInfo.senderPort + " : " + json.toString());
        }
        if (updateInfo.senderAddress != null && updateInfo.senderAddress.length() > 0 && updateInfo.senderPort > 0) {
          LNSListener.udpTransport.sendPacket(json,
                  InetAddress.getByName(updateInfo.senderAddress), updateInfo.senderPort);
        }

        LocalNameServer.updateCacheEntry(confirmPkt);
        // record some stats
        LocalNameServer.incrementUpdateResponse(confirmPkt.getName() //, confirmPkt.getRecordKey()
                );
        String msg = updateInfo.getUpdateStats(confirmPkt);
        if (StartLocalNameServer.debugMode) {
          GNS.getLogger().info(msg);
        }
        GNS.getStatLogger().info(msg);
      }
    } else {
      // if update failed, invalidate active name servers
      // SendUpdatesTask will create a task to get new actives
      // TODO: create SendActivesRequestTask here and delete update info.
      LocalNameServer.invalidateActiveNameServer(confirmPkt.getName()//, confirmPkt.getRecordKey()
              );
      GNS.getLogger().fine(" Update Request Sent To An Invalid Active Name Server. ERROR!! Actives Invalidated");

    }

  }
}