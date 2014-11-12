package edu.umass.cs.gns.gigapaxos;

import edu.umass.cs.gns.nsdesign.nodeconfig.InterfaceNodeConfig;
import edu.umass.cs.gns.nio.*;
import edu.umass.cs.gns.nio.nioutils.PacketDemultiplexerDefault;
import edu.umass.cs.gns.nsdesign.nodeconfig.SampleNodeConfig;
import edu.umass.cs.gns.nsdesign.Replicable;
import edu.umass.cs.gns.nsdesign.packet.Packet;
import edu.umass.cs.gns.nsdesign.packet.Packet.PacketType;
import edu.umass.cs.gns.paxos.AbstractPaxosManager;
import edu.umass.cs.gns.paxos.PaxosConfig;
import edu.umass.cs.gns.gigapaxos.multipaxospacket.FailureDetectionPacket;
import edu.umass.cs.gns.gigapaxos.multipaxospacket.FindReplicaGroupPacket;
import edu.umass.cs.gns.gigapaxos.multipaxospacket.PaxosPacket;
import edu.umass.cs.gns.gigapaxos.multipaxospacket.PaxosPacket.PaxosPacketType;
import edu.umass.cs.gns.gigapaxos.multipaxospacket.RequestPacket;
import edu.umass.cs.gns.gigapaxos.paxosutil.*;
import edu.umass.cs.gns.gigapaxos.paxosutil.MessagingTask;
import edu.umass.cs.gns.util.MultiArrayMap;
import edu.umass.cs.gns.util.Util;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
@author V. Arun
 * @param <NodeIDType>
 */

/* PaxosManager is the primary interface to create
 * and use paxos by creating a paxos instance. 
 * 
 * PaxosManager manages all paxos instances at a node.
 * There is typically one paxos manager per machine. This 
 * class could be static, but it is not so that we can test 
 * emulations involving multiple "machines" within a JVM.
 * 
 * PaxosManager has four functions at a machine that are
 * useful across paxos instances of all applications on 
 * the machine: (1) logging, (2) failure detection, 
 * (3) messaging, and (4) paxos instance mapping. The 
 * fourth is key to allowing the manager to demultiplex
 * incoming messages to the appropriate application paxos
 * instance.
 * 
 * Testability: This class is unit-testable by running 
 * the main method.
 */
public class PaxosManager<NodeIDType> extends AbstractPaxosManager<NodeIDType> {
	public static final boolean DEBUG=NIOTransport.DEBUG;

	private static final long MORGUE_DELAY = 30000;
	private static final boolean MAINTAIN_CORPSES=false;
	private static final int PINSTANCES_CAPACITY = 2000000;
	private static final int LEVELS = 6;
	private static final boolean CRASH_AND_RECOVER_ME_OPTION = false;
	private static final boolean HIBERNATE_OPTION = false;
	private static final boolean PAUSE_OPTION = true;
	private static final long DEACTIVATION_PERIOD = 30000; // 30s default
	private static final boolean ONE_PASS_RECOVERY = true;
	private static final int MAX_POKE_RATE = 1000; // per sec

	// final
	private final AbstractPaxosLogger paxosLogger;  // logging
	private final FailureDetection FD;  // failure detection
	private final Messenger messenger;  // messaging
	private final int myID;
	private final Replicable myApp; // default app for all paxosIDs

	// non-final
	private final MultiArrayMap<String,PaxosInstanceStateMachine> pinstances; // paxos instance mapping
	private final HashMap<String, PaxosInstanceStateMachine> corpses; // stopped paxos instances about to be incinerated
	protected final HashMap<String, ActivePaxosState> activePaxii; // active paxos instances, hopefully small in number
	private final Timer timer = new Timer();

	private boolean hasRecovered = false;

	private static Boolean closed = false; // need this to be static so DB can be closed gracefully
	private static int processing = 0; // need this to be static so DB can be closed gracefully

	/* Note: PaxosManager itself maintains no NIO transport
	 * instance as it delegates all communication
	 * related activities to other objects. PaxosManager
	 * is only responsible for managing state for and 
	 * demultiplexing incoming packets to a number of 
	 * paxos instances at this node.
	 */

	private static Logger log = Logger.getLogger(PaxosManager.class.getName()); //GNS.getLogger();;

	public PaxosManager(NodeIDType id, InterfaceNodeConfig nc, InterfaceJSONNIOTransport niot, Replicable pi, PaxosConfig pc) {
		this.myID = id.hashCode(); // FIXME: Assumes NodeIDType can be case to String and back
		this.myApp = pi;
		this.FD = new FailureDetection(this.myID, nc, niot, pc);
		this.pinstances = new MultiArrayMap<String,PaxosInstanceStateMachine>(PINSTANCES_CAPACITY, LEVELS);
		this.corpses = new HashMap<String,PaxosInstanceStateMachine>();
		this.activePaxii = new HashMap<String,ActivePaxosState>();
		this.messenger = new Messenger(this.myID, niot);
		this.paxosLogger = new DerbyPaxosLogger(this.myID, (pc!=null?pc.getPaxosLogFolder():null), this.messenger);

		timer.schedule(new Deactivator(), DEACTIVATION_PERIOD); // periodically remove active state for idle paxii

		if(TESTPaxosConfig.getCleanDB()) {while(!this.paxosLogger.removeAll());}

		// Networking is needed for replaying messages during recovery
		//if (niot.getClass().equals(JSONNIOTransport.class)) {
		niot.addPacketDemultiplexer(new PaxosPacketDemultiplexer(this)); // so paxos packets will come to me.
                // WESTY REMOVED THIS BECAUSE niot is NOT A JSONNIOTransport
		//if (!((JSONNIOTransport) niot).isStarted()) (new Thread(((JSONNIOTransport) niot))).start();
		//}
		initiateRecovery();
	}

	/* We need to be careful with pause/unpause and createPaxosInstance as there are potential
	 * cyclic dependencies. The call chain is as below, where "info" is the information needed
	 * to create a paxos instance. The notation"->?" means the call may or may not happen, 
	 * which is why the recursion breaks after at most one step.
	 * 
	 * On recovery:
	 * initiateRecovery() -> recover(info) -> createPaxosInstance(info) -> getInstance(paxosID)
	 *   ->? unpause(paxosID) ->? createPaxosInstance(info)
	 * Upon createPaxosInstance any time after recovery, the same chain as above is followed.
	 * 
	 * On deactivate:
	 * deactivate(paxosID) ->? pause(paxosID) // may or may not be successful
	 * 
	 * On incoming packet:
	 * handleIncomingPacket() -> getInstance(paxosID) ->? unpause(paxosID) ->? createPaxosInstance(info)
	 */

	public boolean createPaxosInstance(String paxosID, short version, Set gms, Replicable app) {
		return this.createPaxosInstance(paxosID, version, this.myID, gms, app, null, true);
	}

  public Set<NodeIDType> getPaxosNodeIDs(String paxosID) {
    return null;
  }

  protected boolean createPaxosInstance(String paxosID, short version, int id, Set<Integer> gms, Replicable app,
			HotRestoreInfo hri, boolean tryRestore) {
		if(this.isClosed() || id!=myID) return false;
		PaxosInstanceStateMachine pism = this.getInstance(paxosID, (hasRecovered && hri==null), tryRestore); 
		if(pism!=null) return false;

		pism = new PaxosInstanceStateMachine(paxosID, version, id, gms, app!=null?app:this.myApp, this, hri);
		pinstances.put(paxosID, pism);
		if(this.hasRecovered()) assert(this.getInstance(paxosID,false,false)!=null);
		/* Note: rollForward can not be done inside the instance as we 
		 * first need to update the instance map here so that networking
		 * (trivially sending message to self) works. 
		 */
		rollForward(paxosID);
		this.FD.monitor(gms);
		return true;
	}

	public void handleIncomingPacket(JSONObject jsonMsg) {
		if(this.isClosed()) return; else {setProcessing(true);}

		PaxosPacketType paxosPacketType;
		try {
			RequestPacket.addDebugInfo(jsonMsg, ("i"+myID));
			assert(Packet.getPacketType(jsonMsg)==PacketType.PAXOS_PACKET /*|| Packet.hasPacketTypeField(jsonMsg)*/);
			paxosPacketType = PaxosPacket.getPaxosPacketType(jsonMsg); // will throw exception if no PAXOS_PACKET_TYPE

			switch (paxosPacketType){
			case FAILURE_DETECT:
				FailureDetectionPacket fdp = new FailureDetectionPacket(jsonMsg);
				FD.receive(fdp);
				break;
			case FIND_REPLICA_GROUP:
				FindReplicaGroupPacket findGroup = new FindReplicaGroupPacket(jsonMsg);
				processFindReplicaGroup(findGroup);
				break;
			default:
				assert(jsonMsg.has(PaxosPacket.PAXOS_ID));
				String paxosID = jsonMsg.getString(PaxosPacket.PAXOS_ID);
				PaxosInstanceStateMachine pism = this.getInstance(paxosID); // exact match including version
				if(pism!=null) pism.handlePaxosMessage(jsonMsg);
				else if(MAINTAIN_CORPSES) {this.findPaxosInstance(jsonMsg, paxosID);} // for recovering group created while crashed
				if(pism==null) log.severe("Node "+myID+" unable to find paxos instance for "+paxosID+":" + jsonMsg);
				break;
			}
		} catch(JSONException je) {
			log.severe("Node " + this.myID + " received bad JSON message: " + jsonMsg); je.printStackTrace();
		}
		setProcessing(false);
	}

	private String propose(String paxosID, RequestPacket requestPacket) throws JSONException {
		if(this.isClosed()) return null;

		boolean matched=false;
		JSONObject jsonReq = requestPacket.toJSONObject();
		PaxosInstanceStateMachine pism = this.getInstance(paxosID);
		if(pism!=null) {
			matched = true;
			jsonReq.put(PaxosPacket.PAXOS_ID, pism.getPaxosID());
			this.handleIncomingPacket(jsonReq); 
		}
		return matched ? paxosID : null;
	}
	public String propose(String paxosID, String requestPacket) {
		RequestPacket request=null;
		String retval=null;
		try {
			JSONObject reqJson = new JSONObject(requestPacket);
			request = new RequestPacket(reqJson);
			retval = propose(paxosID, request);
		} catch(JSONException je) {
			log.severe("Could not parse proposed request string as multipaxospacket.RequestPacket");
			je.printStackTrace();
		}
		return retval;
	}
	// FIXME: Need to change AbstractPaxosManager
	public String proposeStop(String paxosID, String value, int version) {
		assert(false) : "Version should be short, not int";
		System.exit(1);
		return null;
	}
	public String proposeStop(String paxosID, String value, short version) {
		PaxosInstanceStateMachine pism = this.getInstance(paxosID);
		if(pism!=null && pism.getVersion()==version) {
			return this.propose(paxosID, value);
		} else {
      return null;
    }
	}

	// FIXME: Unclear how this will be used. May cause problems with ongoing DB transactions.
	public synchronized void resetAll() {
		this.pinstances.clear();
		this.corpses.clear();
		this.paxosLogger.removeAll();
	}

	private static synchronized void closeAll() {{closed=true;}}
	private static synchronized boolean allClosed() {{return closed;}}

	public void close() {
		log.info("Node " + myID + " invoking closeAll");
		closeAll();
		waitToFinishAll();
		this.paxosLogger.close();
	}
	private boolean isClosed() {return allClosed();}

	public static long getDeactivationPeriod() {return DEACTIVATION_PERIOD;}

	/********************* End of public methods ***********************/

	/* Separate method only for instrumentation */
	private synchronized PaxosInstanceStateMachine getInstance(String paxosID, boolean tryHotRestore, boolean tryRestore) {
		long methodEntryTime = System.currentTimeMillis();
		PaxosInstanceStateMachine pism = pinstances.get(paxosID); // exact match including version
		if(pism==null && ((tryHotRestore && this.unpause(paxosID)) || (tryRestore && this.restore(paxosID)))) 
			pism = pinstances.get(paxosID);
		PaxosInstrumenter.update("getInstance", methodEntryTime);
		return pism;
	}
	private synchronized PaxosInstanceStateMachine getInstance(String paxosID) {
		return this.getInstance(paxosID, true, true);
	}

	/* For each paxosID in the logs, this method creates the corresponding
	 * paxos instance and rolls it forward from the last checkpointed state.
	 * 
	 * Synchronized because this method invokes an incremental read on
	 * the database that currently does not support parallelism. But 
	 * the "synchronized" qualifier here is not necessary for
	 * correctness.
	 */
	private synchronized void initiateRecovery() {
		boolean found=false;
		int groupCount=0, freq=1;
		log.info("Node " + this.myID + " beginning to recover checkpoints");
		System.out.print("Node " + this.myID + " beginning to recover checkpoints: ");
		while(this.paxosLogger.initiateReadCheckpoints(true)); // acquires lock
		RecoveryInfo pri=null;
		while((pri = this.paxosLogger.readNextCheckpoint(true))!=null) {
			found = true;
			assert(pri.getPaxosID()!=null);
			// start paxos instance, restore app state from checkpoint if any and roll forward
			this.recover(pri.getPaxosID(), pri.getVersion(), this.myID, pri.getMembers(), myApp); 
			if((++groupCount)%freq==0) {freq*=2; System.out.print(" " + groupCount);}
		}
		this.paxosLogger.closeReadAll(); // releases lock
		log.info((groupCount>0?"\n":"")+"Node " + this.myID + " has recovered checkpoints for " + groupCount + " paxos groups");
		if(!found) {
			log.warning("No checkpoint state found for node " + this.myID +". This can only happen if\n" +
					"(1) the node is newly joining the system, or\n(2) the node previously crashed before " +
					"completing even a single checkpoint, or\n(3) the node's checkpoint was manually deleted.");
		}
		int logCount=0; freq=1;
		if(ONE_PASS_RECOVERY) { // roll forward all logged messages in a single pass
			System.out.print("\nNode " + this.myID + " beginning to roll forward logged messages: ");
			log.info("Node " + this.myID + " beginning to roll forward logged messages");
			while(this.paxosLogger.initiateReadMessages()); // acquires lock
			PaxosPacket packet=null;
			while((packet = this.paxosLogger.readNextMessage())!=null) {
				try {
					this.handleIncomingPacket(packet.toJSONObject());
					if((++logCount)%freq==0) {freq*=2; System.out.print(" " + logCount);}
				} catch(JSONException je) {je.printStackTrace();}
			}
			this.paxosLogger.closeReadAll(); // releases lock
			log.info((logCount>0?"\n":"")+"Node " + this.myID + " has rolled forward " + logCount + 
				" messages in a single pass across " + groupCount + " paxos groups");
		}

		this.pokeAll();
		this.hasRecovered = true;
		System.out.println("\nNode "+this.myID + " recovery complete");
	}
	protected boolean hasRecovered() {return this.hasRecovered;}
	protected boolean hasRecovered(String paxosID) {
		boolean recovered = false;
		if(ONE_PASS_RECOVERY && this.hasRecovered()) recovered = true;
		else if(!ONE_PASS_RECOVERY) {
			ActivePaxosState activeState = this.getActiveState(false, paxosID);
			assert(activeState!=null);  // FIXME: just to test if this ever happens
			recovered = activeState!=null && activeState.hasRecovered();
		}
		return recovered;
	}
	private synchronized void pokeAll() {
		if(PaxosInstanceStateMachine.POKE_ENABLED) {
			log.info("Node " + this.myID + " beginning to poke all instances");
			while(this.paxosLogger.initiateReadCheckpoints(false)); // acquires lock
			RecoveryInfo pri=null;
			RateLimiter rl = new RateLimiter(MAX_POKE_RATE);
			int count=0, freq=1;
			while((pri = this.paxosLogger.readNextCheckpoint(false))!=null) {
				String paxosID = pri.getPaxosID();
				if(paxosID!=null) {
					PaxosInstanceStateMachine pism = this.getInstance(paxosID);
					if(pism!=null) pism.sendTestPaxosMessageToSelf();
					rl.record();
					if((++count)%freq==0) {freq*=2; System.out.print(" " + count);}
				}
			}
			this.paxosLogger.closeReadAll(); // releases lock
			log.info((count>0?"\n":"")+"Node " + this.myID + " has finished poking all instances");
		}
	}


	/* All messaging is done using PaxosMessenger and MessagingTask. 
	 * This method 
	 */
	protected void send(MessagingTask mtask)  throws JSONException, IOException {
		if(mtask==null) return;
		if(mtask instanceof LogMessagingTask) {
			AbstractPaxosLogger.logAndMessage(this.paxosLogger, (LogMessagingTask)mtask, this.messenger);
		} else {
			messenger.send(mtask);
		}
	}

	/* synchronized because we want to move the paxos instance atomically
	 * from pinstances to corpses. If not atomic, it can result in the 
	 * corpse (to-be) getting resurrected if a packet for the instance 
	 * arrives in between.
	 * 
	 * kill completely removes all trace of the paxos instance unlike
	 * hibernate or pause. The copy of the instance kept in corpses
	 * will not be revived; in fact, is there temporarily only to make 
	 * sure that the instance does not get re-created.
	 */
	protected synchronized void kill(PaxosInstanceStateMachine pism) {
		if(pism==null || this.isClosed()) return;
		while(!pism.kill()) {log.severe("Problem stopping paxos instance "+pism.getPaxosID());}
		this.pinstances.remove(pism.getPaxosID()); 
		this.corpses.put(pism.getPaxosID(), pism);
		timer.schedule(new Cremator(pism.getPaxosID(), this.corpses), MORGUE_DELAY);
	}
	// For testing. Similar to hibernate without checkpoint and immediately restore (from older checkpoint)
	protected void crashAndRecoverMe(PaxosInstanceStateMachine pism) {
		if(pism==null || this.isClosed()) return;
		if(CRASH_AND_RECOVER_ME_OPTION) {
			log.severe("OVERLOAD: Restarting overloaded coordinator node " + this.myID + "of " + pism.getPaxosID());
			pism.forceStop();
			this.pinstances.remove(pism.getPaxosID());
			this.recover(pism.getPaxosID(), pism.getVersion(), pism.getNodeID(), pism.getMembers(), pism.getApp(), true);
		}
	}
	// Checkpoint and go to sleep on disk. Currently not used.
	protected synchronized boolean hibernate(PaxosInstanceStateMachine pism) {
		if(pism==null || this.isClosed()) return false;
		boolean hibernated = false;
		if(HIBERNATE_OPTION) {
			log.info("Node "+myID+ " trying to hibernate " + pism.getPaxosID());
			boolean stopped = pism.tryForcedCheckpointAndStop(); // could also do forceStop() here, but might be wasteful
			if(stopped) {
				this.pinstances.remove(pism.getPaxosID());
				hibernated = true;
				log.info("Node "+this.myID+ " sucessfully hibernated " + pism.getPaxosID());
			}
		}
		return hibernated;
	}
	// Undo hibernate. Will rollback, so not very efficient.
	private synchronized boolean restore(String paxosID) {
		if(this.isClosed()) return false;
		boolean restored=false;
		if(HIBERNATE_OPTION) {
			PaxosInstanceStateMachine pism=null;
			if((pism = this.pinstances.get(paxosID))!=null) return true;
			log.info("Trying to restore node " + this.myID + " of " + paxosID);
			RecoveryInfo pri = this.paxosLogger.getRecoveryInfo(paxosID);
			if(pri!=null) pism = this.recover(paxosID, pri.getVersion(), this.myID, pri.getMembers(), this.myApp, true);
			if(pism!=null) {
				restored=true;
				log.info("Node"+myID+" successfully restored hibernated instance " + paxosID);
			}
			else log.warning("Node"+myID+" unable to restore instance " + paxosID);
		} 
		return restored;
	}
	// Like hibernate but without checkpointing or subsequent recovery
	protected synchronized boolean pause(PaxosInstanceStateMachine pism) {
		if(pism==null || this.isClosed()) return false;
		boolean paused = false;
		if(PAUSE_OPTION) {
			if(DEBUG) log.info("Node "+this.myID+ " trying to pause " + pism.getPaxosID());
			long pauseInitTime = System.currentTimeMillis();
			paused = pism.tryPause();  
			if(paused) {
				this.pinstances.remove(pism.getPaxosID());
				PaxosInstrumenter.update("pause", pauseInitTime);
				if(DEBUG) log.info("Node "+this.myID+ " sucessfully paused " + pism.getPaxosID());
			} else if(DEBUG) log.info("Failed to pause " + pism);
		}
		return paused;
	}
	// Hot restores from disk, i.e., restores quickly without need for rollback
	private synchronized boolean unpause(String paxosID) {
		if(this.isClosed()) return false;
		boolean restored = false;
		if(PAUSE_OPTION) {
			if(this.pinstances.get(paxosID)!=null) return true;
			HotRestoreInfo hri = this.paxosLogger.unpause(paxosID);
			if(hri!=null) {
				//if(DEBUG) 
				log.info("Node"+myID+" successfully hot restored paused instance " + paxosID);
				restored = this.createPaxosInstance(hri.paxosID, hri.version, myID, 
						Util.arrayToIntSet(hri.members), this.myApp, hri, false);
			}
			else log.fine("Node"+myID+" unable to hot restore instance " + paxosID);
		}
		return restored;
	}

	/* Create paxos instance restoring app state from checkpoint if any and roll forward */
	private PaxosInstanceStateMachine recover(String paxosID, short version, int id, int[] members, Replicable app, boolean restoration) {
		if(DEBUG) log.info("Node " +this.myID + " " + paxosID + ":" + version + " recovering");
		this.createPaxosInstance(paxosID, version, id, Util.arrayToIntSet(members), app, null, false);
		PaxosInstanceStateMachine pism = (this.getInstance(paxosID, true, false));
		return pism;
	}
	/* After rollForward, recovery is complete. In particular, we don't have 
	 * to wait for any more processing of messages, e.g., out of order decisions
	 * to "settle", because the only important thing is to replay and 
	 * process ACCEPTs and PREPAREs so as to bring the acceptor state
	 * up to speed, which is a purely local and non-blocking 
	 * sequence of operations. Coordinator state in general is not 
	 * recoverable; the easiest way to recover it is to simply call
	 * checkRunForCoordinator, which will happen automatically
	 * upon the receipt of any external packet.
	 */
	private void rollForward(String paxosID) {
		if(!ONE_PASS_RECOVERY || this.hasRecovered())  {
			if(DEBUG) log.info("Node " +this.myID + " " + paxosID + " about to roll forward: ");
			AbstractPaxosLogger.rollForward(paxosLogger, paxosID, messenger);
			ActivePaxosState activeState = this.getActiveState(false, paxosID);
			if(activeState!=null) activeState.setRecovered();
			PaxosInstanceStateMachine pism = (this.getInstance(paxosID, true, false));
			assert(this.getInstance(paxosID, false, false)!=null);
			if(pism!=null) pism.sendTestPaxosMessageToSelf();
		}
	}
	private PaxosInstanceStateMachine recover(String paxosID, short version, int id, int[] members, Replicable app) {
		return this.recover(paxosID, version, id, members, app, false);
	}

	private void findPaxosInstance(JSONObject jsonMsg, String paxosID) throws JSONException {
		log.warning("Node "+ this.myID + " received paxos message for non-existent instance " + paxosID);
		PaxosInstanceStateMachine zombie = this.corpses.get(paxosID);
		if(jsonMsg.has(PaxosPacket.PAXOS_VERSION)) {
			short version = (short)jsonMsg.getInt(PaxosPacket.PAXOS_VERSION);
			if(zombie==null || (short)(zombie.getVersion()-version)<0) findReplicaGroup(jsonMsg, paxosID, version);
		}
	}

	protected void heardFrom(int id) {this.FD.heardFrom(id);}
	protected boolean isNodeUp(int id) {return (FD!=null ? FD.isNodeUp(id) :false);}
	protected boolean lastCoordinatorLongDead(int id) {return (FD!=null ? FD.lastCoordinatorLongDead(id) : true);}
	protected AbstractPaxosLogger getPaxosLogger() {return paxosLogger;}
	protected Messenger getMessenger() {return this.messenger;}

	/****************** Start of methods to gracefully finish processing **************/
	private static synchronized void setProcessing(boolean b) {
		if(b) processing++; else processing--; 
		if(processing==0) PaxosManager.class.notify();
	}
	private static synchronized boolean getProcessing() {
		return processing>0;
	}
	protected static synchronized void waitToFinishAll() {
		try {
			while(getProcessing()) {PaxosManager.class.wait();}
		} catch(InterruptedException ie) {ie.printStackTrace();}
	}
	/****************** End of methods to gracefully finish processing **************/

	/* monitor means start sending the node(s) pings.
	 * We don't expect anything in return. It should 
	 * really be called amAlive instead of monitor.
	 */
	private void monitor(Set<Integer> nodes) {FD.monitor(nodes);}
	/* Stop sending pings to the node */
	private void unMonitor(int id) {FD.unMonitor(id);}

	private void printLog(String paxosID) {
		System.out.println("State for " + paxosID + ": Checkpoint: " + this.paxosLogger.getStatePacket(paxosID));
	}
	// send a request asking for your group
	private void findReplicaGroup(JSONObject msg, String paxosID, short version) throws JSONException {
		FindReplicaGroupPacket findGroup = new FindReplicaGroupPacket(this.myID, msg); // paxosID and version should be within
		int nodeID = FindReplicaGroupPacket.getNodeID(msg);
		if(nodeID >= 0) {
			try {
				this.send(new MessagingTask(nodeID, findGroup));
			} catch(IOException ioe) {ioe.printStackTrace();}
		} else log.severe("Can't find group member in paxosID:version " + paxosID + ":" + version);
	}
	// process a request or send an answer
	private void processFindReplicaGroup(FindReplicaGroupPacket findGroup) throws JSONException {
		MessagingTask mtask = null;
		if(findGroup.group==null && findGroup.nodeID!=this.myID) { // process a request
			PaxosInstanceStateMachine pism = this.getInstance(findGroup.getPaxosID());
			if(pism!=null && pism.getVersion()==findGroup.getVersion()) {
				FindReplicaGroupPacket frgReply = new FindReplicaGroupPacket(pism.getMembers(), findGroup);
				mtask = new MessagingTask(findGroup.nodeID, frgReply);
			}
		} else if(findGroup.group!=null && findGroup.nodeID==this.myID) { // process an answer
			PaxosInstanceStateMachine pism = this.getInstance(findGroup.getPaxosID());
			if(pism==null || (pism.getVersion()-findGroup.getVersion())<0) {
				// kill lower versions if any and create new paxos instance
				if(pism.getVersion()-findGroup.getVersion()<0) this.kill(pism);
				this.createPaxosInstance(findGroup.getPaxosID(), findGroup.getVersion(), 
						this.myID, Util.arrayToIntSet(findGroup.group), myApp, null, false);
			}
		}
		try {
			if(mtask!=null) this.send(mtask);
		} catch(IOException ioe) {ioe.printStackTrace();}
	}

	/*************************** Start of activePaxii related methods **********************/
	private void deactivate() {
		if(this.activePaxii.isEmpty()) return;
		Object[] actives = this.getActivePaxosIDs(); // FIXME: should try to avoid cloning
		int count=0;
		for(Object active : actives) {
			String paxosID = (String)active;
			ActivePaxosState activeState = this.getActiveState(false, paxosID);
			if(!isClosed() && activeState.isLongIdle()) {
				boolean paused = pause(pinstances.get(paxosID)); // pause instance
				if(paused) {
					this.removeActiveState(paxosID);
					count++;
					if(DEBUG) log.info("Node "+myID+ " deactivating idle instance " + paxosID);					}
			}
		}
		log.info("Node "+myID+ " deactivated " + count + " idle instances; total #actives = " + 
				activePaxii.size() + "; average_getInstance_delay = " + 
				Util.mu(PaxosInstrumenter.get("getInstance")) + "; average_pause_delay = " + 
				Util.mu(PaxosInstrumenter.get("pause")));
	}

	private Object[] getActivePaxosIDs() {
		synchronized(this.activePaxii) {
			return this.activePaxii.keySet().toArray();
		}
	}
	private void removeActiveState(String paxosID) {
		synchronized(this.activePaxii) {
			this.activePaxii.remove(paxosID);
		}
	}

	protected ActivePaxosState getActiveState(boolean active, String paxosID) {
		synchronized(this.activePaxii) {
			ActivePaxosState activeState = this.activePaxii.get(paxosID);
			if(activeState==null) this.activePaxii.put(paxosID, (activeState=new ActivePaxosState(paxosID)));
			if(active) activeState.justActive();
			return activeState;
		}
	}
	/*************************** End of activePaxii related methods **********************/


	private class Cremator extends TimerTask {
		String id = null;
		HashMap<String,PaxosInstanceStateMachine> map = null;
		Cremator(String paxosID, HashMap<String,PaxosInstanceStateMachine> zombies) {
			this.id = paxosID;
			this.map = zombies;
		}
		public void run() {
			synchronized(map) {
				map.remove(id);
			}
		}
	}

	/* Both deactivates, i.e., removes temporary active paxos state, and
	 * pauses, i.e., swaps safety-critical paxos state to disk.  
	 */
	private class Deactivator extends TimerTask {
		public void run() {
			deactivate(); // should not schedule periodically as the previous one may not have finished
			timer.schedule(new Deactivator(), PaxosManager.DEACTIVATION_PERIOD);
		}
	}

	/************************* Testing methods below ***********************************/
	protected synchronized void waitRecover() throws InterruptedException {wait();}
	protected InterfaceJSONNIOTransport getNIOTransport() {return this.FD.getNIOTransport();}

	public static void main(String[] args) {
		int nNodes = 3;

		TreeSet<Integer> gms = new TreeSet<Integer>();
		int[] members = new int[nNodes];
		for(int i=0; i<nNodes; i++) {
			gms.add(i+100); 
			members[i] = i+100;
		}

		SampleNodeConfig snc = new SampleNodeConfig(2000);
		snc.localSetup(gms);
		JSONMessageExtractor[] jmws = new JSONMessageExtractor[nNodes];
		JSONNIOTransport[] niots = new JSONNIOTransport[nNodes];
		PaxosManager[] pms = new PaxosManager[nNodes];
		TESTPaxosReplicable[] apps = new TESTPaxosReplicable[nNodes];
		TESTPaxosConfig.crash(members[0]);
		TESTPaxosConfig.setSendReplyToClient(false);

		try {
			for(int i=0; i<nNodes; i++) {
				System.out.println("Testing: initiating node " + i);
				jmws[i] = new JSONMessageExtractor(new PacketDemultiplexerDefault());
				niots[i] = new JSONNIOTransport(members[i], snc, jmws[i]);
				new Thread(niots[i]).start();

				for(int j=0; j<apps.length; j++) apps[i] = new TESTPaxosReplicable(niots[i]);
				pms[i] = new PaxosManager(members[i], snc, niots[i], apps[i], null);
				if(!TESTPaxosConfig.isCrashed(members[i])) pms[i].monitor(gms);
			}

			System.out.println("Initiated all " + nNodes + " paxos managers with failure detectors, sleeping for a few seconds..\n");
			Thread.sleep(1000);

			for(int i=0; i<nNodes; i++) {
				for(int j=0; j<nNodes; j++) {
					if(i!=j) {
						System.out.println("Testing: Node " + members[i] + " finds node " + 
								members[j] + " " + (pms[i].isNodeUp(members[j]) ? "up":"down"));
					}
				}
			}

			// We don't really test with multiple groups as they are independent, but this is useful for memory testing
			int paxosGroups=2; 

			System.out.println("\nTesting: Creating " + paxosGroups + " paxos groups each with " + nNodes + 
					" members each, one each at each of the " + nNodes + " nodes");
			//PaxosInstanceStateMachine[] pisms = new PaxosInstanceStateMachine[nNodes*paxosGroups];
			for(int i=0; i<nNodes; i++) {
				int k=1;
				for(int j=0; j<paxosGroups; j++) {
					pms[i].createPaxosInstance("paxos"+j, (short)0, members[i], gms, apps[i], null, false);
					if(paxosGroups>1000 && (j%k==0 || j%100000==0)) {System.out.print(j+" "); k*=2;}
				}
				for(int m=1; m<nNodes; m++) {
					pms[0].unMonitor(m);
				}
				System.out.println("");
			}

			System.out.println("\nTesting: Finished creating " + paxosGroups + " paxos groups each with " + nNodes + 
					" members each, one each at each of the " + nNodes + " nodes");
			Thread.sleep(1000);
			/*********** Finished creating paxos instances for testing *****************/


			ScheduledExecutorService execpool = Executors.newScheduledThreadPool(5);
			class ClientRequestTask implements Runnable {
				private final RequestPacket request;
				private final PaxosManager paxosManager;
				ClientRequestTask(RequestPacket req, PaxosManager pm) {
					request = req;
					paxosManager = pm;
				}
				public void run() {
					try {
						JSONObject reqJson =  request.toJSONObject();
						Packet.putPacketType(reqJson, PacketType.PAXOS_PACKET);
						paxosManager.propose(request.getPaxosID(), request);
					} catch(JSONException e) {
						e.printStackTrace();
					}
				}
			}
			int numReqs=1000;
			RequestPacket[] reqs = new RequestPacket[numReqs];
			ScheduledFuture<?>[] futures = new ScheduledFuture[numReqs];
			String[] GUIDs = new String[paxosGroups];
			for(int i=0; i<GUIDs.length; i++) GUIDs[i] = "paxos"+i;

			for(int i=0;i<nNodes; i++) {
				while(!TESTPaxosConfig.isCrashed(members[i]) && !TESTPaxosConfig.getRecovered(members[i], GUIDs[0])) {
					log.info("Waiting for " + members[i] + " to recover ");
					Thread.sleep(1000);
				}
				log.info("Node "+members[i] + " finished recovery including rollback.");
			}
			int[] recoverySlots = new int[numReqs];
			int maxRecoverySlot = -1;
			for(int i=0;i<nNodes; i++) {
				recoverySlots[i] = apps[i].getNumCommitted(GUIDs[0]);
				System.out.println("Node "+members[i]+" recovered at "+recoverySlots[i]);
				if(recoverySlots[i] > maxRecoverySlot) maxRecoverySlot = recoverySlots[i];
			}

			int numExceptions=0;
			double scheduledDelay=0; 
			for(int i=0; i<numReqs; i++) {
				reqs[i] = new RequestPacket(0, i, "[ Sample write request numbered " + i + " ]", false);
				reqs[i].putPaxosID(GUIDs[0], (short)0);
				JSONObject reqJson =  reqs[i].toJSONObject();
				Packet.putPacketType(reqJson, PacketType.PAXOS_PACKET);
				try {
					ClientRequestTask crtask = new ClientRequestTask(reqs[i], pms[1]);
					futures[i] = (ScheduledFuture<?>)execpool.schedule(crtask, (long)scheduledDelay, TimeUnit.MILLISECONDS);
					scheduledDelay += 0;
				} catch(Exception e) {
					e.printStackTrace();
					continue; 
				}
			}
			log.info("Waiting for future gets");
			for(int i=0; i<numReqs; i++) {
				try {
					futures[i].get();
				} catch(Exception e) {
					e.printStackTrace();
					numExceptions++;
				}
			}
			log.info("All futures finished; numExceptions="+numExceptions);
			Thread.sleep(1000);

			while(true) {
				boolean done = false;
				for(int i=0; i<nNodes; i++) {
					if(apps[i].getNumCommitted(GUIDs[0]) >= maxRecoverySlot+numReqs) done = true;
				}
				if(done) break;
			}

			int numCommitted=0;
			for(int i=0; i<nNodes; i++) {
				for(int j=i+1; j<nNodes; j++) {
					if(!TESTPaxosConfig.isCrashed(members[i]) && !TESTPaxosConfig.isCrashed(members[j])) {
						int committed1 = apps[i].getNumCommitted(GUIDs[0]);
						int committed2 = apps[j].getNumCommitted(GUIDs[0]);
						int diff = committed1-committed2; if(diff<0) diff = -diff;
						while(committed1!=committed2) {
							if(committed1>committed2) apps[j].waitToFinish(GUIDs[0], committed1);
							else if(committed1<committed2) apps[i].waitToFinish(GUIDs[0], committed2);
							//(committed1>committed2 ? apps[j] : apps[i]).waitToFinish();
							log.info("Waiting : (slot1,hash1)=(" +committed1 +","+
									apps[i].getHash(GUIDs[0])+"(; (slot2,hash2="+committed2+","+apps[j].getHash(GUIDs[0])+")");
							Thread.sleep(1000);
							committed1 = apps[i].getNumCommitted(GUIDs[0]);
							committed2 = apps[j].getNumCommitted(GUIDs[0]);
						}
						assert(committed1==committed2) : 
							"numCommitted@" + i + "="+committed1 + ", numCommitted@" + j +"="+committed2;
						numCommitted = apps[i].getNumCommitted(GUIDs[0]);
						assert(apps[i].getHash(GUIDs[0]) == apps[j].getHash(GUIDs[0])) : 
							("Waiting : (slot1,hash1)=(" +committed1 +","+
									apps[i].getHash(GUIDs[0])+"(; (slot2,hash2="+committed2+","+apps[j].getHash(GUIDs[0])+")");; // SMR invariant
					}
				}
			}
			String preemptedReqs = "[ ";
			int numPreempted=0;
			for(int i=0; i<numReqs; i++) {
				if(!TESTPaxosConfig.isCommitted(i)){
					preemptedReqs += (i + " ");
					numPreempted++;
				}
			}
			preemptedReqs += "]";

			DecimalFormat df = new DecimalFormat();
			df.setMaximumFractionDigits(2);

			System.out.println("\n\nTest completed. Executed " + numCommitted + " requests consistently including " + 
					(numReqs-numPreempted) + " of " + numReqs + " received requests;\nPreempted requests = " + preemptedReqs + 
					"; numExceptions="+ numExceptions+"; average message log time="+df.format(PaxosInstrumenter.get("logDelay"))+"ms.\n"+
					"\nNote that it is possible for the test to be successful even if the number of consistently\n" +
					"executed requests is less than the number of received requests as paxos only guarantees\n" +
					"consistency, i.e., that all replicas executed requests in the same order, not that all requests\n" +
					"issued will get executed. The latter property can be achieved by clients reissuing requests\n" +
					"until successfully executed. With reissuals, clients do need to worry about double execution,\n" +
					"so they should be careful. A client is not guaranteed to get a failure message if the request fails,\n" +
					"e.g., if the replica receiving a request dies immediately. If the client uses a timeout to detect\n" +
					"failure and thereupon reissue its request, it is possible that both the original and re-issued\n" +
					"requests are executed. Clients can get around this problem by using sequence numbers within\n" +
					"their app, reading the current sequence number, and then trying to commit their write provided the\n" +
					"sequence number has not changed in the meantime. There are other alternatives, but all of these\n" +
					"are application-specific; they are not paxos's problem\n");
			for(int i=0; i<nNodes;i++) {
				pms[i].printLog(GUIDs[0]);
			} System.out.println("");
			//Thread.sleep(1000000);
			System.exit(1);
		} catch(Exception e) {
			e.printStackTrace();
		}
	}
}