/*
 * Copyright (C) 2015
 * University of Massachusetts
 * All Rights Reserved 
 *
 * Initial developer(s): Westy.
 */
package edu.umass.cs.gns.newApp.clientCommandProcessor;

import edu.umass.cs.gns.clientCommandProcessor.ClientRequestHandlerInterface;
import edu.umass.cs.gns.reconfiguration.json.reconfigurationpackets.BasicReconfigurationPacket;
import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

/**
 *
 * @author westy
 * @param <NodeIDType>
 */
public interface EnhancedClientRequestHandlerInterface<NodeIDType> extends ClientRequestHandlerInterface<NodeIDType> {
  
  public NodeIDType getRandomReplica();

  public NodeIDType getRandomRCReplica();

  public NodeIDType getFirstReplica();

  public NodeIDType getFirstRCReplica();

  public void sendRequestToRandomReconfigurator(BasicReconfigurationPacket req) throws JSONException, IOException;
  
  public void sendRequestToReconfigurator(BasicReconfigurationPacket req, NodeIDType id) throws JSONException, IOException;
  
  public boolean handleEvent(JSONObject json) throws JSONException;
  
  /**
   * Adds a mapping between a ServiceName request and a LNSREquestID.
   * Provides backward compatibility between old Add and Remove record code and new name service code.
   * 
   * @param name
   * @param id 
   */
  public void addRequestNameToIDMapping(String name, int id);
  
  /**
   * Looks up the mapping between a ServiceName request and a LNSREquestID.
   * Provides backward compatibility between old Add and Remove record code and new name service code.
   * 
   * @param name
   * @return 
   */
  public Integer getRequestNameToIDMapping(String name);
  
  /**
   * Removes the mapping between a ServiceName request and a LNSREquestID.
   * Provides backward compatibility between old Add and Remove record code and new name service code.
   * 
   * @param name
   * @return the request if or null if if can't be found
   */
  public Integer removeRequestNameToIDMapping(String name);
  
  public Object getActiveReplicaID();

}
