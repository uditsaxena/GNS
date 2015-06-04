package edu.umass.cs.gigapaxos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONException;
import org.json.JSONObject;

import edu.umass.cs.gigapaxos.paxospackets.AcceptPacket;
import edu.umass.cs.gigapaxos.paxospackets.AcceptReplyPacket;
import edu.umass.cs.gigapaxos.paxospackets.PValuePacket;
import edu.umass.cs.gigapaxos.paxospackets.PaxosPacket;
import edu.umass.cs.gigapaxos.paxospackets.PreparePacket;
import edu.umass.cs.gigapaxos.paxospackets.PrepareReplyPacket;
import edu.umass.cs.gigapaxos.paxospackets.ProposalPacket;
import edu.umass.cs.gigapaxos.paxospackets.RequestPacket;
import edu.umass.cs.gigapaxos.paxospackets.StatePacket;
import edu.umass.cs.gigapaxos.paxospackets.SyncDecisionsPacket;
import edu.umass.cs.gigapaxos.paxospackets.PaxosPacket.PaxosPacketType;
import edu.umass.cs.gigapaxos.paxosutil.ActivePaxosState;
import edu.umass.cs.gigapaxos.paxosutil.Ballot;
import edu.umass.cs.gigapaxos.paxosutil.HotRestoreInfo;
import edu.umass.cs.gigapaxos.paxosutil.LogMessagingTask;
import edu.umass.cs.gigapaxos.paxosutil.MessagingTask;
import edu.umass.cs.gigapaxos.paxosutil.PaxosInstanceCreationException;
import edu.umass.cs.gigapaxos.paxosutil.RequestInstrumenter;
import edu.umass.cs.gigapaxos.paxosutil.SlotBallotState;
import edu.umass.cs.gigapaxos.testing.TESTPaxosConfig;
import edu.umass.cs.reconfiguration.reconfigurationutils.RequestParseException;
import edu.umass.cs.utils.Keyable;
import edu.umass.cs.utils.Util;
import edu.umass.cs.utils.DelayProfiler;

/**
@author V. Arun
 */

/* This class is the top-level paxos class per instance or paxos group
 * on a machine. This is the only class that exposes public methods. 
 * Actually even this class will soon become "protected" as the only way 
 * to use it will be through the corresponding PaxosManager even if there 
 * is just one paxos application running on the machine.
 * 
 * This class delegates much of the interesting paxos actions to 
 * PaxosAcceptorState and PaxosCoordinator. It delegates all messaging
 * to PaxosManager's PaxosMessenger. It is "managed", i.e., its paxos
 * group is created and its incoming packets are demultiplexed, by
 * its PaxosManager. It's logging is handled by an implementation of
 * AbstractPaxosLogger.
 * 
 * The high-level organization is best reflected in handlePaxosMessage, 
 * a method that delegates processing to the acceptor or coordinator
 * and gets back a messaging task, e.g., receiving a prepare message
 * will probably result in a prepare-reply messaging task, and so on.
 * 
 * Space: An inactive PaxosInstanceStateMachine, i.e., whose corresponding
 * application is currently not processing any requests, uses ~225B *total*. 
 * Here is the breakdown:
 * PaxosInstanceStateMachine final fields: ~80B
 * PaxosAcceptor: ~90B
 * PaxosCoordinatorState: ~60B 
 * Even in an inactive paxos instance, the total *total* space is much more 
 * because of PaxosManager (that internally uses FailureDetection) etc.,
 * but all that state is not incurred per paxos application, just per 
 * machine. Thus, if we have S=10 machines and N=10M applications each 
 * using paxos with K=10 replicas one each at each machine, each machine
 * has 10M PaxosInstanceStateMachine instances that will use about 
 * 2.25GB (10M*225B). The amount of space used by PaxosManager and others
 * is small and depends only on S, not N or K.
 * 
 * When actively processing requests, the total space per paxos instance
 * can easily go up to thousands of bytes. But we are unlikely to be
 * processing requests across even hundreds of thousands of different 
 * applications simultaneously if each request finishes executing in 
 * under a second. For example, if a single server's execution
 * throughput is 10K requests/sec and each request takes 100ms to
 * finish executing (including paxos coordination), then the number 
 * of active *requests* at a machine is on average ~100K. The 
 * number of active paxos instances at that machine is at most 
 * the number of active requests at that machine.
 *  
 * Can this state machine get stuck permanently? Hopefully not coz here
 * is how we deal with loss. Under high load, messages to an unreachable 
 * destination can get dropped. NIO will buffer up to a threshold limit 
 * and keep trying to reconnect and send to the destination, and Messenger 
 * will keep trying to retransmit with exponential backoff, but if a 
 * destination is just dead, the send buffer will eventually fill up
 * and we have to stop retransmitting (or exhaust heap space). Now if
 * the destination comes back up, it would have missed some messages.
 * If more than a majority of destinations are highly loaded and they 
 * also crash at some point (i.e., they become !isConnected() from
 * NIO's view) and they subsequently come back up, they could have
 * missed some messages. If a majority miss some message, say a prepare 
 * or an accept, and there is nothing in the code here (not just in 
 * Messenger as that might eventually give up) to trigger their 
 * retransmission, the state machine can get stuck forever. We address
 * these as follows.
 * 
 * FIXED:  If a majority miss a prepare, 
 * the coordinator may never get elected as follows. The minority of 
 * acceptors who did receive the prepare will assume the prepare's 
 * sender as the current coordinator. The rest might still think the 
 * previous coordinator is the current coordinator. All acceptors 
 * could be thinking that their current coordinator is up, so nobody 
 * will bother running for coordinator. To break this impasse, we need
 * to resend the prepare. This has been now incorporated in the
 * checkRunForCoordinator method that periodically checks to see if
 * we need to "(re)run for coordinator" (for the same ballot) if we
 * have been waiting for too long (having neither received a prepare
 * majority nor a preemption) for the ballot to complete.
 * 
 * FIXED: If a majority miss an accept, but any messages are still 
 * being received at all, then the loss will eventually get fixed by
 * a check similar to checkRunForCoordinator that upon receipt
 * of every message will poke the local coordinator to recommander the 
 * next-in-line accept if the accept has been waiting for too long 
 * (for a majority or preemption). Both the prepare and accept waiting
 * checks are quick O(1) operations.
 * 
 * So can the machine still get stuck forever? Hopefully not! 
 * 
 * Testability: This class is not unit-testable as almost all methods
 * depend on other classes. Both PaxosManager as well as TESTPaxosMain 
 * test this class.
 */
public class PaxosInstanceStateMachine implements Keyable<String> {
	/* If false, the paxosID is represented as a byte[], so we must invoke 
	 * getPaxosID() as infrequently as possible.
	 */
	private static final boolean PAXOS_ID_AS_STRING=false;
	
	// must be >= 1, does not depend on anything else
	protected static final int INTER_CHECKPOINT_INTERVAL = 100; 
	
	// out-of-order-ness prompting synchronization, must be >=1
	protected static final int SYNC_THRESHOLD = 4*INTER_CHECKPOINT_INTERVAL;
	
	// max decisions gap when reached will prompt checkpoint transfer
	protected static final int MAX_SYNC_DECISIONS_GAP = INTER_CHECKPOINT_INTERVAL;
	
	// used by PaxosCoordinatorState to determine if overloaded
	protected static final int MAX_OUTSTANDING_LOAD = 100*INTER_CHECKPOINT_INTERVAL;
	
	// poke instance in the beginning to prompt coordinator election
	protected static final boolean POKE_ENABLED = false;

	/************ final Paxos state that is unchangeable after creation ***************/
	private final int[] groupMembers; 
	private final Object paxosID; // Object to allow easy testing across byte[] and String
	private final short version;
	private final PaxosManager<?> paxosManager;
	private final InterfaceReplicable clientRequestHandler;

	/************ Non-final paxos state that is changeable after creation *******************/
	private PaxosAcceptor paxosState=null;	// uses ~125B of empty space when not actively processing requests
	private PaxosCoordinator coordinator=null; // uses just a single pointer's worth of space unless I am a coordinator
	/************ End of non-final paxos state ***********************************************/

	// static, so does not count towards space.
	private static Logger log = PaxosManager.getLogger();//Logger.getLogger(PaxosInstanceStateMachine.class.getName()); 

	PaxosInstanceStateMachine(String groupId, short version, int id, Set<Integer> gms, 
			InterfaceReplicable app, String initialState, PaxosManager<?> pm, HotRestoreInfo hri) {

		/**************** final assignments ***********************
		 * A paxos instance is born with a paxosID, version
		 * this instance's node ID, the application request handler, 
		 * the paxos manager, and the group members.
		 */
		this.paxosID = PAXOS_ID_AS_STRING ? groupId : groupId.getBytes();
		this.version = version;
		this.clientRequestHandler = app;
		this.paxosManager = pm;
		assert(gms!=null && gms.size()>0);
		Arrays.sort(this.groupMembers = Util.setToIntArray(gms)); 
		/**************** End of final assignments *******************/

		/* All non-final state is store in PaxosInstanceState (for acceptors)
		 * or in PaxosCoordinatorState (for coordinators) that inherits from
		 * PaxosInstanceState.
		 */
		if(pm!=null && hri==null) {initiateRecovery(initialState);}
		else if(hri!=null && initialState==null) hotRestore(hri);
		else testingNoRecovery(); // used only for testing size
		assert (hri == null || initialState == null) : "Can not specify initial state for existing, paused paxos instance";
		
		createActiveState(); // initialize active state, so that we can be deactivated if idle

		if (!TESTPaxosConfig.MEMORY_TESTING && hri == null)
			log.log(Level.INFO,
					"Initialized paxos {0}{1}{2}{3}{4}{5}{6}{7}{8}{9}{10}",
					new Object[] {
							(this.paxosState.getBallotCoord() == this.getMyID() ? "COORDINATOR "
									: "instance "),
							this.getPaxosIDVersion(),
							" at node ",
							this.getMyID(),
							" with ",
							groupMembers.length,
							" members. ",
							this.paxosState.toString(),
							this.coordinator,
							(initialState == null ? "{recoveredState=["
									+ Util.prefix(this.getCheckpointState(), 64)
									: "{initialState=[" + initialState), "]}" });
	}
	
	public String getKey() {return this.getPaxosID();}
	public Short getVersion() {return this.version;}
	public String getPaxosIDVersion() {return this.getPaxosID()+":"+this.getVersion();}

	protected String getPaxosID() {return (paxosID instanceof String ? (String)paxosID : new String((byte[])paxosID)); }
	protected int[] getMembers() {return this.groupMembers;}
	protected int getNodeID() {return this.getMyID();}
	protected InterfaceReplicable getApp() {return this.clientRequestHandler;}
	protected PaxosManager<?> getPaxosManager() {return this.paxosManager;}
	protected int getMyID() {return (this.paxosManager!=null ? this.paxosManager.getMyID() : -1);}
	public String toString() {return this.getNodeState() /*+ " " + (this.paxosState!=null ? this.paxosState.toString():"null") +
			(this.coordinator.exists()?this.coordinator.toString():"null")*/;}

	/* isStopped()==true means that this paxos instance is
	 * dead and completely harmless (even if the underlying object has
	 * not been garbage collected by the JVM. In particular, it can 
	 * NOT make the app execute requests or send out paxos messages 
	 * to the external world.
	 */
	public synchronized boolean isStopped() {return this.paxosState.isStopped();}
	protected boolean forceStop() { // not synchronized as coordinator can die anytime anyway 
		this.coordinator.forceStop(); 
		this.paxosState.forceStop(); // 
		return true;
	}
	protected boolean kill() {  // removes all database state and can not be recovered anymore
		this.forceStop();
		AbstractPaxosLogger.kill(this.paxosManager.getPaxosLogger(), getPaxosID()); // drop all log state
		// kill implies reset app state
		this.clientRequestHandler.updateState(getPaxosID(), null);
		return true;
	}
	protected void setActive() {
		this.paxosState.setActive();
	}
	protected boolean isActive() {
		return this.paxosState.isActive();
	}

	private String getCheckpointState() {
		SlotBallotState sbs = this.paxosManager.getPaxosLogger()
				.getSlotBallotState(getPaxosID(), getVersion());
		return sbs != null ? sbs.state : null;
	}

	protected void handlePaxosMessage(JSONObject msg) throws JSONException {
		long methodEntryTime = System.currentTimeMillis();
		/* Note: Because incoming messages may be handled concurrently, some messages
		 * may continue to get processed for a little while after a stop has been
		 * executed and even after isStopped() is true (because isStopped() was
		 * false when those messages came in here). But that is okay coz these 
		 * messages can not spawn unsafe outgoing messages (as messaging is 
		 * turned off for all but DECISION or CHECKPOINT_STATE packets) and
		 * can not change any disk state.
		 */
		if(this.paxosState.isStopped()) return;
		if(((short)msg.getInt(PaxosPacket.PAXOS_VERSION))!=this.getVersion()) return;
		if(TESTPaxosConfig.isCrashed(this.getMyID())) return; // Tester says I have crashed
		boolean recovery = PaxosPacket.isRecovery(msg); // recovery means we won't message any replies
		/* The reason we should not process regular messages until this instance has 
		 * rolled forward is that it might respond to a prepare with a list of 
		 * accepts fetched from disk that may be inconsistent with its acceptor state.
		 */
		if(!this.paxosManager.hasRecovered(this) && !recovery) return; // don't process regular messages until ready for rollForward
		this.markActiveState(); 

		PaxosPacketType msgType = PaxosPacket.getPaxosPacketType(msg);
		log.log(Level.FINE, "{0}{1}{2}{3}{4}", new Object[] {this, " received ", msgType, ": ", msg});

		MessagingTask[] mtasks = new MessagingTask[2];
		// check upon every message 
		mtasks[0] = (!recovery ? !this.coordinator.isActive() ? checkRunForCoordinator() : this.pokeLocalCoordinator() : null); 

		MessagingTask mtask=null;
		switch(msgType) {
		case REQUEST:
			mtask = handleRequest(new RequestPacket(msg));
			// send RequestPacket to current coordinator
			break;
			// replica --> coordinator
		case PROPOSAL:
			mtask = handleProposal(new ProposalPacket(msg));
			// either send ProposalPacket to current coordinator or send AcceptPacket to all
			break;
			// coordinator --> replica
		case DECISION:
			mtask = handleCommittedRequest(new PValuePacket(msg));
			// send nothing, but log decision
			break;
			// coordinator --> replica
		case PREPARE:
			mtask = handlePrepare(new PreparePacket(msg));
			// send PreparePacket prepare reply to coordinator
			break;
			// replica --> coordinator
		case PREPARE_REPLY:
			mtask = handlePrepareReply(new PrepareReplyPacket(msg));
			// send AcceptPacket[] to all
			break;
			// coordinator --> replica
		case ACCEPT:
			mtask = handleAccept(new AcceptPacket(msg));
			// send AcceptReplyPacket to coordinator
			break;
			// replica --> coordinator
		case ACCEPT_REPLY:
			mtask = handleAcceptReply(new AcceptReplyPacket(msg));
			// send PValuePacket decision to all
			break;
		case SYNC_DECISIONS:
			mtask = handleSyncDecisionsPacket(new SyncDecisionsPacket(msg));
			// send SynchronizeReplyPacket to sender
			break;
		case CHECKPOINT_STATE:
			mtask = handleCheckpoint(new StatePacket(msg));
			break;
		case NO_TYPE:
			if(!TESTPaxosConfig.MEMORY_TESTING) 
				log.log(Level.FINE, "{0}{1}{2}{3}", new Object[]{this, " received a \"self-poke\" NO_TYPE message"});
			break;
		default: 
			assert(false) : "Paxos instance received an unrecognizable packet: " + msg;
		}
		mtasks[1] = mtask;
				
		DelayProfiler.update("handlePaxosMessage", methodEntryTime);

		this.checkIfTrapped(msg, mtasks[1]); // just to print a warning if needed
		if(!recovery) {this.sendMessagingTask(mtasks);} 
	}

	/************** Start of private methods ****************/

	/* Invoked both when a paxos instance is first created and when
	 * it recovers after a crash. It is all the same as far as the
	 * paxos instance is concerned (provided we ensure that
	 * the app state after executing the first request (slot 0)
	 * is checkpointed, which we do).
	 */
	private boolean initiateRecovery(String initialState) {
		String pid = this.getPaxosID();
		// only place where version is checked
		SlotBallotState slotBallot = this.paxosManager.getPaxosLogger()
				.getSlotBallotState(pid, this.getVersion());
		if (!TESTPaxosConfig.MEMORY_TESTING && slotBallot != null)
			log.log(Level.INFO, "{0}{1}{2}", new Object[] { this,
					" recovered state: ",
					(slotBallot != null ? slotBallot.state : "NULL") });

		// update app state
		if (slotBallot != null && slotBallot.state != null)
			if (!this.clientRequestHandler.updateState(pid, slotBallot.state))
				throw new PaxosInstanceCreationException(
						"Unable to update app state with " + slotBallot.state);

		this.coordinator = new PaxosCoordinator(); // just a shell class
		// initial coordinator is assumed, not prepared
		if (slotBallot == null && roundRobinCoordinator(0) == this.getMyID())
			this.coordinator.makeCoordinator(0, this.getMyID(), getMembers(),
					(initialState != null ? 1 : 0), true); // slotBallot==null
		/*
		 * Note: We don't have to create coordinator state here. It will get
		 * created if needed when the first external (non-recovery) packet is
		 * received. But we create the very first coordinator here as otherwise
		 * it is possible that no coordinator gets elected as follows: the
		 * lowest ID node wakes up and either upon an external or self-poke
		 * message sends a prepare, but gets no responses because no other node
		 * is up yet. In this case, the other nodes when they boot up will not
		 * run for coordinator, and the lowest ID node will not resend its
		 * prepare if no more requests come, so the first request could be stuck
		 * in its pre-active queue for a long time.
		 */

		this.paxosState = new PaxosAcceptor(
				slotBallot != null ? slotBallot.ballotnum : 0,
				slotBallot != null ? slotBallot.coordinator : this
						.roundRobinCoordinator(0),
				slotBallot != null ? slotBallot.slot + 1 : 0, null);
		if (slotBallot == null)
			this.putInitialState(initialState);
		if (slotBallot == null)
			TESTPaxosConfig.setRecovered(this.getNodeID(), pid, true);

		return true; // return value will be ignored
	}

	private boolean hotRestore(HotRestoreInfo hri) {
		assert(this.paxosState==null && this.coordinator==null); // called from constructor only
		log.log(Level.FINE, "{0}{1}{2}{3}", new Object[]{"Node",this.getMyID(), " hot restoring with ", hri});
		this.coordinator = new PaxosCoordinator();
		this.coordinator.hotRestore(hri);
		this.paxosState = new PaxosAcceptor(hri.accBallot.ballotNumber,
				hri.accBallot.coordinatorID, hri.accSlot,hri);
		return true;
	}
	
	private boolean putInitialState(String initialState) {
		if (this.getPaxosManager() == null || initialState==null)
			return false;
		this.handleCheckpoint(new StatePacket(initialBallot(), 0, initialState));
		this.paxosState.setGCSlotAfterPuttingInitialSlot();
		return true;
	}
	
	private Ballot initialBallot() {
		return new Ballot(0, this.roundRobinCoordinator(0));
	}
	/* The one method for all message sending. 
	 * Protected coz the logger also calls this. 
	 */
	protected void sendMessagingTask(MessagingTask mtask) {
		if(mtask==null || mtask.isEmpty()) return; 
		if(this.paxosState!=null && this.paxosState.isStopped() && 
				!mtask.msgs[0].getType().equals(PaxosPacketType.DECISION) &&
				!mtask.msgs[0].getType().equals(PaxosPacketType.CHECKPOINT_STATE)) return;
		if(TESTPaxosConfig.isCrashed(this.getMyID())) return; 

		log.log(Level.FINE, "{0}{1}{2}", new Object[]{this, " sending: ", mtask});
		mtask.putPaxosIDVersion(this.getPaxosID(), this.getVersion());
		try {
			paxosManager.send(mtask);
		} catch (IOException ioe) {
			log.severe(this.getNodeState() +" encountered IOException while sending " + mtask);
			ioe.printStackTrace();
			/* We can't throw this exception upward because it will get sent all
			 * the way back up to PacketDemultiplexer whose incoming packet initiated
			 * this whole chain of events. It seems silly for PacketDemultiplexer
			 * to throw an IOException caused by the sends resulting from processing
			 * that packet. So we should handle this exception right here. But what
			 * should we do? We can ignore it as the network does not need to be 
			 * reliable anyway. Revisit as needed.
			 */
		} catch(JSONException je) {
			/* Same thing for other exceptions. Nothing useful to do here */
			log.severe(this.getNodeState()+" encountered JSONException while sending "+mtask);
			je.printStackTrace();
		}
	}
	private void sendMessagingTask(MessagingTask[] mtasks) throws JSONException {
		for(MessagingTask mtask : mtasks) this.sendMessagingTask(mtask);
	}
	protected void sendTestPaxosMessageToSelf() {
		if(POKE_ENABLED) {
			try {
				JSONObject msg = new JSONObject();
				msg.put(PaxosPacket.PAXOS_ID, this.getPaxosID());
				msg.put(PaxosPacket.PAXOS_VERSION, this.getVersion());
				msg.put(PaxosPacket.PAXOS_PACKET_TYPE, PaxosPacketType.NO_TYPE.getInt());
				this.handlePaxosMessage(msg);
			} catch(JSONException je) {je.printStackTrace();}
		}
	}

	/* "Phase0"
	 * Event: Received a request from a client.
	 * 
	 * Action: Call handleProposal which will send the corresponding
	 * proposal to the current coordinator.
	 */
	private MessagingTask handleRequest(RequestPacket msg) throws JSONException {
		log.log(Level.FINE, "{0}{1}{2}", new Object[]{this, " Phase0/CLIENT_REQUEST: ", msg});
		RequestInstrumenter.received(msg, msg.clientID, this.getMyID());
		msg.setEntryReplica(getMyID());
		return handleProposal(new ProposalPacket(0, msg));
	}

	/* "Phase0"->Phase2a:
	 * Event: Received a proposal [request, slot] from any node.
	 * 
	 * Action: If a non-coordinator node receives a proposal, send to 
	 * the coordinator. Otherwise, propose it to acceptors with a good 
	 * slot number (thereby initiating phase2a for this request).
	 * 
	 * Return: A send either to a coordinator of the proposal or to all
	 * replicas of the proposal with a good slot number. 
	 */
	private MessagingTask handleProposal(ProposalPacket proposal) throws JSONException {
		MessagingTask mtask=null; // could be multicast or unicast to coordinator.
		assert(proposal.getEntryReplica()!=-1);
		if(proposal.getForwardCount()==0) proposal.setReceiptTime(); // first receipt into the system
		else RequestInstrumenter.received(proposal, proposal.getForwarderID(), this.getMyID());
		if(this.coordinator.exists(this.paxosState.getBallot())) { // propose to all
			log.log(Level.FINE, "{0}{1}{2}{3}{4}{5}", new Object[]{this, " issuing Phase2a/ACCEPT after ", (System.currentTimeMillis() - 
					proposal.getCreateTime()), " ms : ",  (this.coordinator.isActive() ? 
							" sending accept for request ":" received pre-active request "), proposal});
			AcceptPacket multicastAccept = null;
			proposal.addDebugInfo("a");
			multicastAccept = this.coordinator.propose(this.groupMembers, proposal); 
			mtask = multicastAccept!=null ? new MessagingTask(this.groupMembers, multicastAccept) : null; // multicast accept to all
			if(multicastAccept!=null) RequestInstrumenter.sent(multicastAccept, this.getMyID(), -1);
		}
		else { // else unicast to current coordinator
			log.log(Level.FINE, "{0}{1}{2}{3}{4}", new Object[]{this, " is not the coordinator, forwarding to ", 
					this.paxosState.getBallotCoord(), " : ", proposal});
			mtask = new MessagingTask(this.paxosState.getBallotCoord(), proposal.setForwarderID(this.getMyID())); // unicast to coordinator
			if(proposal.isPingPonging()) {
				log.warning(this.getNodeState() + " dropping ping-ponging proposal: " + proposal);
				mtask = this.checkRunForCoordinator(true);
			} else 	proposal.addDebugInfo("f");
		}
		return mtask;
	}


	/* Phase1a
	 * Event: Received a prepare request for a ballot, i.e. that
	 * ballot's coordinator is acquiring proposing rights for all
	 * slot numbers (lowest uncommitted up to infinity)
	 * 
	 * Action: This node needs to check if it has accepted a higher
	 * numbered ballot already and if not, it can accept this
	 * ballot, thereby promising not to accept any lower ballots.
	 * 
	 * Return: Send prepare reply with proposal values previously
	 * accepted to the sender (the received ballot's coordinator).
	 */
	private MessagingTask handlePrepare(PreparePacket prepare) throws JSONException{
		this.paxosManager.heardFrom(prepare.ballot.coordinatorID); // FD optimization, not necessary
		PrepareReplyPacket prepareReply = this.paxosState.handlePrepare(prepare, this.paxosManager.getMyID());  
		if(prepareReply==null) return null; // can happen only if acceptor is stopped
		if(prepare.isRecovery()) return null; // no need to get accepted pvalues from disk during recovery as networking is disabled anyway
		
		// we may also need to look into disk if ACCEPTED_PROPOSALS_ON_DISK is true
		if(PaxosAcceptor.ACCEPTED_PROPOSALS_ON_DISK) {
			prepareReply.accepted.putAll(this.paxosManager.getPaxosLogger().getLoggedAccepts(
					this.getPaxosID(), prepare.firstUndecidedSlot));
			for (PValuePacket pvalue : prepareReply.accepted.values())
				assert (this.paxosState.getBallot().compareTo(pvalue.ballot) >= 0) : this
						.getNodeState() + ":" + pvalue;
		}
		/* We need to special case this as there is no logged ACCEPT corresponding
		 * to the initial state, so the coordinator must be explicitly informed
		 * of firstSlot that is higher than gcSlot+1.
		 * 
		 * FIXME: better to change gcSlot to be equal to (as opposed to one less than)
		 * the checkpoint slot.
		 */
		if (prepareReply.accepted.isEmpty()
				&& this.paxosState.getGCSlot() == -1
				&& this.paxosState.getSlot() == 1)
			prepareReply.setFirstSlot(0);

		log.log(Level.FINE, "{0}{1}{2}{3}{4}{5}{6}", new Object[]{this.getNodeState() , " sending ", 
				prepareReply.accepted.size(), " accepted pvalues starting at ", 
				prepareReply.getMinSlot(), "; firstUndecidedSlot = ", prepare.firstUndecidedSlot});
		
		LogMessagingTask mtask = new LogMessagingTask(prepareReply.ballot.coordinatorID, prepareReply, (PaxosPacket)prepare);
		return mtask;
	}

	/* Phase1b
	 * Event: Received a reply to my ballot preparation request.
	 * 
	 * Action: If the reply contains a higher ballot, we must resign.
	 * Otherwise, if we acquired a majority with the receipt of this 
	 * reply, send all previously accepted (but uncommitted) requests
	 * reported in the prepare replies, each in its highest reported 
	 * ballot, to all replicas. These are the proposals that get 
	 * carried over across a ballot change and must be re-proposed.
	 * 
	 * Return: A list of messages each of which has to be multicast
	 * (proposed) to all replicas.
	 */
	private MessagingTask handlePrepareReply(PrepareReplyPacket prepareReply) {
		this.paxosManager.heardFrom(prepareReply.acceptor); // FD optimization, not necessary
		MessagingTask mtask=null;
		ArrayList<ProposalPacket> preActiveProposals=null;
		ArrayList<AcceptPacket> acceptList = null;
		
		if((preActiveProposals = this.coordinator.getPreActivesIfPreempted(prepareReply, this.groupMembers))!=null) {
			log.info(this.getNodeState()+" ("+this.coordinator.getBallotStr()+ ") election PREEMPTED by " + prepareReply.ballot);
			if(!preActiveProposals.isEmpty()) mtask = new MessagingTask(prepareReply.ballot.coordinatorID, 
					MessagingTask.toPaxosPacketArray(preActiveProposals.toArray()));
		}
		else if((acceptList = this.coordinator.handlePrepareReply(prepareReply, this.groupMembers))!=null && !acceptList.isEmpty()) {
			mtask = new MessagingTask(this.groupMembers, MessagingTask.toPaxosPacketArray(acceptList.toArray()));
			log.info(this.getNodeState()+ " elected coordinator; sending ACCEPTs for: " + mtask);
		}

		return mtask; // Could be unicast or multicast 
	}

	/* Phase2a 
	 * Event: Received an accept message for a proposal with some ballot.
	 * 
	 * Action: Send back current or updated ballot to the ballot's coordinator. 
	 */
	private MessagingTask handleAccept(AcceptPacket accept) throws JSONException {
		this.paxosManager.heardFrom(accept.sender); // FD optimization
		RequestInstrumenter.received(accept, accept.sender, this.getMyID());
		Ballot ballot = this.paxosState.acceptAndUpdateBallot(accept, this.getMyID()); 
		if(ballot==null) {return null;}// can happen only if acceptor is stopped. 

		this.garbageCollectAccepted(accept.getMedianCheckpointedSlot()); 
		if(accept.isRecovery()) return null; // recovery ACCEPTS do not need any reply

		AcceptReplyPacket acceptReply = new AcceptReplyPacket(this.getMyID(), ballot, accept.slot, 
				lastCheckpointSlot(this.paxosState.getSlot()-1), accept);
		AcceptPacket toLog = (accept.ballot.compareTo(ballot)>=0 ? accept : null); // lower ballot => no logging, only accept reply
		MessagingTask mtask = toLog!=null ? new LogMessagingTask(accept.sender, acceptReply, toLog) :
			new MessagingTask(accept.sender, acceptReply);
		RequestInstrumenter.sent(acceptReply, this.getMyID(), accept.sender);

		return mtask;
	}
	/* We don't need to implement this. Accept logs are pruned 
	 * while checkpointing anyway, which is enough. Worse, it 
	 * is probably inefficient to touch the disk for GC upon
	 * every new gcSlot (potentially every accept and decision).
	 */
	private void garbageCollectAccepted(int gcSlot) {}	

	/* Phase2b
	 * Event: Received a reply to an accept request, i.e. to 
	 * a request to accept a proposal from the coordinator.
	 * 
	 * Action: If this reply results in a majority for the
	 * corresponding proposal, commit the request and notify all.
	 * If this preempts a proposal being coordinated because
	 * it contains a higher ballot, forward to the preempting
	 * coordinator in the higher ballot reported.
	 * 
	 * Return: The committed proposal if any to be multicast to 
	 * all replicas, or the preempted proposal if any to be
	 * unicast to the preempting coordinator. Null if neither.
	 */
	private MessagingTask handleAcceptReply(AcceptReplyPacket acceptReply) {
		this.paxosManager.heardFrom(acceptReply.acceptor); // FD optimization
		RequestInstrumenter.received(acceptReply, acceptReply.acceptor, this.getMyID());
		PValuePacket committedPValue = this.coordinator.handleAcceptReply(this.groupMembers, acceptReply);
		if(committedPValue==null) return null;

		MessagingTask multicastDecision=null; MessagingTask unicastPreempted=null; // separate variables only for code readability
		// Could also call handleCommittedRequest below or even just rely on broadcast to all
		if(committedPValue.getType()==PaxosPacket.PaxosPacketType.DECISION) {
			committedPValue.addDebugInfo("d"); 
			//this.handleCommittedRequest(committedPValue);
			multicastDecision = new MessagingTask(this.groupMembers, committedPValue); // inform everyone of the decision
		} else if (committedPValue.getType()==PaxosPacket.PaxosPacketType.PREEMPTED) { 
			/* Could drop the request, but we forward the preempted proposal as a no-op to 
			 * the new coordinator for testing purposes. The new(er) coordinator information 
			 * is within acceptReply. Note that our coordinator status may still be active 
			 * and it will be so until all of its requests have been preempted. Note also 
			 * that our local acceptor might still think we are the coordinator. The only 
			 * evidence of a new coordinator is in acceptReply that must have reported
			 * a higher ballot if we are here, hence the assert.
			 * 
			 * Warning: Can not forward the preempted request as-is to the new coordinator
			 * as this can result in multiple executions of a request. Although the 
			 * multiple executions will have different slot numbers and will not violate
			 * paxos safety, this is extremely undesirable for most applications. 
			 */
			assert(committedPValue.ballot.compareTo(acceptReply.ballot) < 0 || committedPValue.hasTakenTooLong()) : 
				(committedPValue +" >= " + acceptReply +", hasTakenTooLong="+committedPValue.hasTakenTooLong());
			if(!committedPValue.isNoop()) {
				// forward only if not already a no-op
				unicastPreempted = new MessagingTask(acceptReply.ballot.coordinatorID, 
					committedPValue.makeNoop().setForwarderID(this.getMyID())); 
				committedPValue.addDebugInfo("f");
			}
			log.info(this.getNodeState() + " forwarding preempted request as no-op to node " + 
					acceptReply.ballot.coordinatorID + ": " + committedPValue);
		}
		return committedPValue.getType()==PaxosPacket.PaxosPacketType.DECISION ? multicastDecision : unicastPreempted;
	}

	/* Phase3
	 * Event: Received notification about a committed proposal.
	 * 
	 * Action: This method is responsible for executing a committed 
	 * request. For this, it needs to call a handler implementing
	 * the PaxosInterface interface.
	 * 	
	 */
	private MessagingTask handleCommittedRequest(PValuePacket committed) {
		RequestInstrumenter.received(committed, committed.ballot.coordinatorID, this.getMyID());

		// Log, extract from or add to acceptor, and execute the request at the app
		if(!committed.isRecovery()) 
			AbstractPaxosLogger.logDecision(this.paxosManager.getPaxosLogger(), committed, this);
		MessagingTask mtask = this.extractExecuteAndCheckpoint(committed);

		TESTPaxosConfig.commit(committed.requestID); 
		
		if (this.paxosState.getSlot() - committed.slot < 0)
			log.log(Level.FINE, "{0}{1}{2}{3}{4}",
					new Object[] { this.getNodeState(), " expecting ",
							this.paxosState.getSlot(),
							" recieved out-of-order commit: ", committed });

		return mtask; 
	}

	private ActivePaxosState createActiveState() {		
		return (this.paxosManager != null ? this.paxosManager.getActiveState(
				false, getPaxosID()) : null);
	}
	private ActivePaxosState markActiveState() {		
		return (this.paxosManager != null ? this.paxosManager.getActiveState(
				true, getPaxosID()) : null);
	}

	// Invoked by handleCommittedRequest above ...
	private MessagingTask fixLongDecisionGaps(PValuePacket committed) {
		if (committed == null)
			return null;

		ActivePaxosState activeState = markActiveState();

		MessagingTask fixGapsRequest = null;
		if (this.shouldSync(committed.slot, this.getPaxosManager().getOutOfOrderLimit()) && activeState.canSync()) {
			fixGapsRequest = this
					.requestMissingDecisions((committed.ballot.coordinatorID));
			if (fixGapsRequest != null) {
				log.info(this.getNodeState() + " fixing gaps: "
						+ fixGapsRequest);
				activeState.justSyncd(); // don't need to put into map again

			}
		}
		return fixGapsRequest;
	}

	private boolean checkIfTrapped(JSONObject incoming, MessagingTask mtask) {
		if(this.isStopped() && mtask!=null) {
			log.warning(this.getNodeState() + " DROPPING message trapped inside stopped " +
					"instance: " + incoming + " ; " + mtask);
			return true;
		}
		return false;
	}
	/* The three actions--(1) extracting the next slot request from the acceptor, 
	 * (2) having the app execute the request, and (3) checkpoint if needed--need
	 * to happen atomically. If the app throws an error while executing the 
	 * request, we need to retry until successful, otherwise, the replicated 
	 * state machine will be stuck. So, essentially, the app has to support
	 * atomicity or the operations have to be idempotent for correctness of
	 * the replicated state machine.
	 * 
	 * This method is protected, not private, because it needs to be called by 
	 * the logger after it is done logging the committed request. Having the 
	 * logger call this method is only space-efficient design alternative.
	 */
	protected synchronized MessagingTask extractExecuteAndCheckpoint(PValuePacket loggedDecision) {
		long methodEntryTime = System.currentTimeMillis(); 
		if(this.paxosState.isStopped()) return null;
		PValuePacket inorderDecision=null;
		int execCount = 0;
		// extract next in-order decision
		while((inorderDecision = this.paxosState.putAndRemoveNextExecutable(loggedDecision))!=null) { 
			log.log(Level.FINE, "{0}{1}{2}", new Object[]{this, " in-order commit: ",inorderDecision}); 
			String pid = this.getPaxosID();

			// execute it until successful, we are *by design* stuck o/w; must be atomic with extraction
			execute(this.clientRequestHandler, inorderDecision.getRequestValue(), inorderDecision.isRecovery());
			execCount++; // +1 for each batch, not for each constituent requestValue

			// checkpoint if needed, must be atomic with the execution 
			if(shouldCheckpoint(inorderDecision) && !inorderDecision.isRecovery()) { 
				this.paxosManager.getPaxosLogger().putCheckpointState(pid, this.version, 
						this.groupMembers, inorderDecision.slot, this.paxosState.getBallot(), 
						this.clientRequestHandler.getState(pid), this.paxosState.getGCSlot());
				log.info(DelayProfiler.getStats());
			}
			// copy epoch final state and kill self if stop request
			if (inorderDecision.isStopRequest()) {
				this.paxosManager.getPaxosLogger().copyEpochFinalCheckpointState(getPaxosID(),getVersion());
				this.paxosManager.kill(this);
			}
		}
		this.paxosState.assertSlotInvariant();
		assert(loggedDecision==null || this.paxosState.getSlot()!=loggedDecision.slot); // otherwise it would've been executed
		if(loggedDecision!=null && !loggedDecision.isRecovery()) DelayProfiler.update("EEC", methodEntryTime, execCount);
		return this.fixLongDecisionGaps(loggedDecision);
	}

	/*
	 * Helper method used above in extractExecuteAndCheckpoint as well as by
	 * PaxosManager for emulating unreplicated execution for testing purposes.
	 */
	protected static boolean execute(InterfaceReplicable app, String[] requestValues, boolean doNotReplyToClient) {
		for(String requestValue : requestValues)
			while (!app.handleRequest(
					getInterfaceRequest(app, requestValue),
					doNotReplyToClient))
				log.severe("App failed to execute request, retrying: "
						+ requestValue);
		return true;
	}
	// Like extractExecuteAndCheckpoint but invoked upon checkpoint transfer
	private synchronized MessagingTask handleCheckpoint(StatePacket statePacket) {
		if(statePacket.slotNumber >= this.paxosState.getSlot()) {
			// put checkpoint in app (like execute)
			if(!this.clientRequestHandler.updateState(getPaxosID(), statePacket.state)) return null;
			// update acceptor (like extract)
			this.paxosState.jumpSlot(statePacket.slotNumber+1);
			// put checkpoint in logger (like checkpoint)
			this.paxosManager.getPaxosLogger().putCheckpointState(this.getPaxosID(), this.version, groupMembers, 
					statePacket.slotNumber, statePacket.ballot, this.clientRequestHandler.getState(getPaxosID())/*statePacket.state*/, this.paxosState.getGCSlot());
			log.info(this.getNodeState()+" inserted checkpoint through handleCheckpoint, next slot = " + this.paxosState.getSlot());
		}
		return extractExecuteAndCheckpoint(null); // coz otherwise we can get stuck as assertSlotInvariant() may not hold here
	}
	/* This method is called by PaxosManager.hibernate that blocks on the
	 * checkpoint operation to finish (unlike regular checkpoints that are 
	 * asynchronously handled by a helper thread). But hibernate is currently
	 * not really used as pause suffices. And PaxosManager methods are likely
	 * called by an executor task anyway, so blocking should be harmless.
	 */
	protected boolean tryForcedCheckpointAndStop() {
		boolean checkpointed=false;
		synchronized(this.paxosState) { synchronized(this.coordinator) { // Ugly nesting, not sure how else to do this correctly
			if(this.paxosState.caughtUp() && this.coordinator.caughtUp()) {
				String pid = this.getPaxosID();
				this.paxosManager.getPaxosLogger().putCheckpointState(pid, this.getVersion(), 
						this.groupMembers, this.paxosState.getSlot(), this.paxosState.getBallot(), 
						this.clientRequestHandler.getState(pid), this.paxosState.getGCSlot());
				checkpointed = true;
				log.info(this.getNodeState() + " forcing checkpoint at slot " + this.paxosState.getSlot() + 
						"; garbage collected accepts upto slot " + this.paxosState.getGCSlot() + "; max committed slot = " + 
						this.paxosState.getMaxCommittedSlot() + (this.paxosState.getBallotCoord()==this.getMyID() ? 
								"; maxCommittedFrontier="+this.coordinator.getMajorityCommittedSlot() : ""));
				this.forceStop();
			}
		}}
		return checkpointed;
	}
	/* Needs to be synchronized so that extractExecuteAndCheckpoint does not
	 * happen concurrently. Likewise handleCheckpoint.
	 */
	protected synchronized boolean forceCheckpoint() {
		String pid = this.getPaxosID();
		this.paxosManager.getPaxosLogger().putCheckpointState(pid, this.getVersion(), 
				this.groupMembers, this.paxosState.getSlot(), this.paxosState.getBallot(), 
				this.clientRequestHandler.getState(pid), this.paxosState.getGCSlot());
		log.info(this.getNodeState() + " forcing checkpoint at slot " + this.paxosState.getSlot() + 
				"; garbage collected accepts upto slot " + this.paxosState.getGCSlot() + "; max committed slot = " + 
				this.paxosState.getMaxCommittedSlot() + (this.paxosState.getBallotCoord()==this.getMyID() ? 
						"; maxCommittedFrontier="+this.coordinator.getMajorityCommittedSlot() : ""));
		return true;
	}
	// Same as tryForcedCheckpointAndStop without checkpoint. **Don't call frivolously.**
	protected boolean tryPause() {
		boolean paused = false;
		synchronized(this.paxosState) { synchronized(this.coordinator) { // Ugly nesting, not sure how else to do this correctly
			if(this.paxosState.caughtUp() && this.coordinator.caughtUp()) {
				HotRestoreInfo hri = new HotRestoreInfo(this.getPaxosID(), this.getVersion(), this.groupMembers, 
						this.paxosState.getSlot(), this.paxosState.getBallot(), this.paxosState.getGCSlot(),
						this.coordinator.getBallot(), this.coordinator.getNextProposalSlot(), 
						this.coordinator.getNodeSlots());
				PaxosAcceptor A = this.paxosState; PaxosCoordinator C = this.coordinator;
				this.forceStop();
				if(!TESTPaxosConfig.MEMORY_TESTING) log.log(Level.FINE, "{0}{1}{2}", new Object[]{this," pausing ", hri});
				paused = this.paxosManager.getPaxosLogger().pause(getPaxosID(), hri.toString());
				if(!paused) {this.paxosState = A; this.coordinator = C;} // revert back if pause failed
			}
		}}
		return paused;
	}

	private boolean shouldCheckpoint(PValuePacket decision) {
		return (decision.slot%this.paxosManager.getInterCheckpointInterval()==0 || decision.isStopRequest());
	}
	private static InterfaceRequest getInterfaceRequest(InterfaceReplicable app, String value) {
		try {
			return app.getRequest(value);
		} catch (RequestParseException e) {
			e.printStackTrace();
		}
		return null;
	}

	/*************************** End of phase 3 methods ********************************/


	/********************** Start of failure detection and recovery methods *****************/

	/* Should be called regularly. Checks whether current ballot
	 * coordinator is alive. If not, it checks if it should try
	 * to be the nest coordinator and if so, it becomes the next
	 * coordinator. This method can be called any time safely 
	 * by any thread.
	 */
	private MessagingTask checkRunForCoordinator() {return this.checkRunForCoordinator(false);}
	private MessagingTask checkRunForCoordinator(boolean forceRun) {
		Ballot curBallot = this.paxosState.getBallot();
		MessagingTask multicastPrepare=null;

		/* 
		 * curBallot is my acceptor's ballot; "my acceptor's coordinator" is that ballot's coordinator.
		 * 
		 *  If I am not already a coordinator at least as high as my acceptor's ballot's coordinator
		 *  AND
		 *  I didn't run too recently
		 *  AND
		 *  (I am my acceptor's coordinator 
		 *      OR (my acceptor's coordinator is dead 
		 *         AND 
		 *         (I am next in line OR the current coordinator has been dead for a really long time)
		 *         )
		 *   )
		 *   OR forceRun
		 */
		if((!this.coordinator.exists(curBallot) && !this.coordinator.ranRecently() && 
				(curBallot.coordinatorID==this.getMyID() // can happen during recovery
				|| 
				(!this.paxosManager.isNodeUp(curBallot.coordinatorID) 
						&& 
						(this.getMyID()==getNextCoordinator(curBallot.ballotNumber+1, this.groupMembers) 
						|| 
						paxosManager.lastCoordinatorLongDead(curBallot.coordinatorID)))))
						|| forceRun) 
		{ 
			/* We normally round-robin across nodes for electing coordinators, e.g., 
			 * node 7 will try to become coordinator in ballotnum such that ballotnum%7==0
			 * if it suspects that the current coordinator is dead. But it is more robust 
			 * to check if it has been a long time since we heard anything from the current 
			 * coordinator and if so, try to become a coordinator ourself even though it
			 * is not our turn. Otherwise, weird partitions can result in loss of liveness, 
			 * e.g., the next-in-line coordinator thinks the current coordinator is up but 
			 * most everyone else thinks the current coordinator is down. Or the next-in-line 
			 * coordinator itself could be dead. The downside of this lasCoordinatorLongDead 
			 * check is that many nodes might near simultaneously try to become coordinator 
			 * with no one succeeding for a while, but this is unlikely to be a problem 
			 * if we rely on the deterministic round-robin rule in the common case and rely 
			 * on the lasCoordinatorLongDead with a longer timeout (much longer 
			 * than the typical node failure detection timeout). 
			 */
			log.info(getNodeState()
					+ " decides to run for coordinator as node "
					+ curBallot.coordinatorID
					+ (curBallot.coordinatorID != this.getMyID() ? " appears to be dead"
							: " has not yet initialized its coordinator"));
			Ballot newBallot = new Ballot(curBallot.ballotNumber + 1,
					this.getMyID());
			if (this.coordinator.makeCoordinator(newBallot.ballotNumber,
					newBallot.coordinatorID, this.groupMembers,
					this.paxosState.getSlot(), false) != null) {
				multicastPrepare = new MessagingTask(this.groupMembers,
						new PreparePacket(newBallot, this.paxosState.getSlot()));
			}
		} else if (this.coordinator.waitingTooLong()) { // just "re-run" by
														// resending prepare
			log.warning(this.getNodeState()
					+ " resending timed out PREPARE; this is only needed under high congestion");
			Ballot newBallot = this.coordinator.remakeCoordinator(groupMembers);
			if (newBallot != null)
				multicastPrepare = new MessagingTask(this.groupMembers,
						new PreparePacket(newBallot, this.paxosState.getSlot()));
		} else if (!this.paxosManager.isNodeUp(curBallot.coordinatorID)
				&& !this.coordinator.exists(curBallot)) { // not my job
			log.info(getNodeState()
					+ " thinks current coordinator "
					+ curBallot.coordinatorID
					+ " is"
					+ (paxosManager
							.lastCoordinatorLongDead(curBallot.coordinatorID) ? " *long* "
							: " ")
					+ "dead, the next-in-line is "
					+ getNextCoordinator(curBallot.ballotNumber + 1,
							this.groupMembers)
					+ (this.coordinator.ranRecently() ? ", and I ran too recently to try again"
							: ""));
		}
		return multicastPrepare;
	}
	private String getBallots() {return "[C:("+(this.coordinator!=null ? this.coordinator.getBallotStr():"null")+
			"), A:("+(this.paxosState!=null ? this.paxosState.getBallotSlot():"null")+")]";}
	private String getNodeState() {return "Node "+this.getMyID()+ " "+ this.getBallots() + ", " + this.getPaxosIDVersion();}

	/* Computes the next coordinator as the node with the smallest ID 
	 * that is still up. We could plug in any deterministic policy here.
	 * But this policy should somehow take into account whether nodes
	 * are up or down. Otherwise, paxos will be stuck if both the 
	 * current and the next-in-line coordinator are both dead.
	 * 
	 * It is important to choose the coordinator in a deterministic
	 * way when recovering, e.g., the lowest numbered node. Otherwise
	 * different nodes may have different impressions of who the 
	 * coordinator is with unreliable failure detectors, but no one
	 * other than the current coordinator may actually ever run
	 * for coordinator. E.g., with three nodes 100, 101, 102, if
	 * 102 thinks 101 is the coordinator, and the other two start
	 * by assuming 100 is the coordinator, then 102's accept 
	 * replies will keep preempting 100's accepts but 101 may 
	 * never run for coordinator as it has no reason to think
	 * there is any problem with 100.
	 */
	private int getNextCoordinator(int ballotnum, int[] members, boolean recovery) {
		for(int i=1; i<members.length; i++) assert(members[i-1] < members[i]);
		if(recovery) return members[0];
		for(int i=0; i<members.length;i++) {
			if(this.paxosManager!=null && this.paxosManager.isNodeUp(members[i])) return members[i];
		}
		return roundRobinCoordinator(ballotnum); 
	}
	private int getNextCoordinator(int ballotnum, int[] members) {
		return this.getNextCoordinator(ballotnum, members, false);
	}
	private int roundRobinCoordinator(int ballotnum) {
		return this.getMembers()[ballotnum % this.getMembers().length];
	}

	// Resend long-waiting accepts. Otherwise, the machine can get stuck.
	private MessagingTask pokeLocalCoordinator() {
		AcceptPacket accept = this.coordinator.reissueAcceptIfWaitingTooLong(this.paxosState.getSlot());
		if(accept!=null) log.info(this.getNodeState() + " resending timed out ACCEPT " + accept);
		MessagingTask reAccept = (accept!=null ? new MessagingTask(this.groupMembers, accept) : null);
		return reAccept;
	}

	/* Event: Received or locally generated a sync request.
	 * Action: Send a sync reply containing missing committed requests to the requester.
	 * If the requester is myself, multicast to all.
	 */
	private MessagingTask requestMissingDecisions(int coordinatorID) {
		ArrayList<Integer> missingSlotNumbers = this.paxosState.getMissingCommittedSlots(this.paxosManager.getMaxSyncDecisionsGap());
		if(missingSlotNumbers==null || missingSlotNumbers.isEmpty()) return null;

		int maxDecision = this.paxosState.getMaxCommittedSlot();
		SyncDecisionsPacket srp =  new SyncDecisionsPacket(this.getMyID(),maxDecision, missingSlotNumbers, 
				this.isMissingTooMuch());

		// send sync request to coordinator or multicast to all but me if I am coordinator
		MessagingTask mtask = coordinatorID!=this.getMyID() ? new MessagingTask(coordinatorID, srp) :
			new MessagingTask(otherGroupMembers(), srp); 
		return mtask;
	}
	
	private boolean shouldSync(int maxDecisionSlot, int threshold) {
		int expectedSlot = this.paxosState.getSlot();
		return (maxDecisionSlot - expectedSlot >= threshold)
				|| ((expectedSlot == 0 || expectedSlot == 1) && (maxDecisionSlot - expectedSlot > 0));
	}

	private boolean isMissingTooMuch() {
		return this.shouldSync(this.paxosState.getMaxCommittedSlot(),
				this.paxosManager.getMaxSyncDecisionsGap());
	}

	// point here is really to request initial state
	protected MessagingTask requestZerothMissingDecision() {
		ArrayList<Integer> missingSlotNumbers = new ArrayList<Integer>();
		missingSlotNumbers.add(0);

		SyncDecisionsPacket srp = new SyncDecisionsPacket(this.getMyID(), 1,
				missingSlotNumbers, true);

		log.info(this + " requesting missing zeroth checkpoint.");
		// send sync request to coordinator or multicast to others if I am coordinator
		MessagingTask mtask = this.paxosState.getBallotCoord() != this
				.getMyID() ? new MessagingTask(
				this.paxosState.getBallotCoord(), srp) : new MessagingTask(
				otherGroupMembers(), srp);
		return mtask;
	}

	// Utility method to get members except myself
	private int[] otherGroupMembers() {
		int[] others = new int[this.groupMembers.length-1];
		int j=0;
		for(int i=0; i<this.groupMembers.length;i++) {
			if(this.groupMembers[i]!=this.getMyID()) others[j++] = this.groupMembers[i];
		}
		return others;
	}

	/* Event: Received a sync reply packet with a list of missing committed requests
	 * Action: Send back all missing committed requests from the log to the sender (replier).
	 * 
	 * We could try to send some from acceptor memory instead of the log, but in general, it is not worth 
	 * the effort. Furthermore, if the sync gap is too much, do a checkpoint transfer.
	 */
	private MessagingTask handleSyncDecisionsPacket(SyncDecisionsPacket syncReply) throws JSONException {
		log.info(this + " received syncReply: " + syncReply);
		int minMissingSlot = syncReply.missingSlotNumbers.get(0); 
		
		// try to get checkpoint
		MessagingTask checkpoint = null;
		if (this.paxosState.getSlot() - minMissingSlot <= 0)
			return null; // I am worse than you
		else if (minMissingSlot - lastCheckpointSlot(this.paxosState.getSlot()) <= 0) {
			checkpoint = handleCheckpointRequest(syncReply);
			if (checkpoint != null)
				minMissingSlot = ((StatePacket) (checkpoint.msgs[0])).slotNumber + 1;
		}

		// get decisions from database (unlikely to have most of them in memory)
		ArrayList<PValuePacket> missingDecisions = this.paxosManager
				.getPaxosLogger().getLoggedDecisions(this.getPaxosID(),
						minMissingSlot, syncReply.maxDecisionSlot);
		for (Iterator<PValuePacket> pvalueIterator = missingDecisions
				.iterator(); pvalueIterator.hasNext();) {
			PValuePacket pvalue = pvalueIterator.next();
			if (!syncReply.missingSlotNumbers.contains(pvalue.slot))
				pvalueIterator.remove(); // filter non-missing
			assert (!pvalue.isRecovery()); // isRecovery() only in rollForward
		}
		MessagingTask unicasts = missingDecisions.isEmpty() ? null
				: new MessagingTask(syncReply.nodeID,
						MessagingTask.toPaxosPacketArray(missingDecisions
								.toArray()));
		if (unicasts != null)
			log.info(this.getNodeState()
					+ " sending missing decisions to node " + syncReply.nodeID
					+ ": " + unicasts);

		// combine checkpoint and missing decisions in unicasts
		MessagingTask mtask = null;
		if (checkpoint != null && unicasts != null && !checkpoint.isEmpty()
				&& !unicasts.isEmpty())
			mtask = new MessagingTask(syncReply.nodeID,
					MessagingTask.toPaxosPacketArray(checkpoint.msgs,
							unicasts.msgs));
		else if (checkpoint != null)
			mtask = checkpoint;
		else if (unicasts != null)
			mtask = unicasts;
		return mtask;
	}

	private int lastCheckpointSlot(int slot) {
		return lastCheckpointSlot(slot, this.paxosManager.getInterCheckpointInterval());
	}
	private static int lastCheckpointSlot(int slot, int checkpointInterval) {
		int lcp = slot - slot%checkpointInterval;
		if(lcp < 0 && ((lcp -= checkpointInterval) > 0)) // wraparound-arithmetic
				lcp = lastCheckpointSlot(Integer.MAX_VALUE, checkpointInterval);
		return lcp;
	}

	/* Event: Received a request for a recent checkpoint presumably
	 * from a replica that has recovered after a long down time. 
	 * Action: Send checkpoint to requester.
	 */
	private MessagingTask handleCheckpointRequest(SyncDecisionsPacket syncReply) {
		/* The assertion below does not mean that the state we actually get will be 
		 * at lastCheckpointSlot() or higher because, even though getSlot() has gotten
		 * updated, the checkpoint to disk may not yet have finished. We have no way 
		 * of knowing other than reading the disk. So we first do a read to check if
		 * the checkpointSlot is at least higher than the minMissingSlot in syncReply.
		 * If the state is tiny, this will double the state fetching overhead as we 
		 * are doing two database reads.
		 */
		assert(syncReply.missingSlotNumbers.get(0) - lastCheckpointSlot(this.paxosState.getSlot()) <= 0);
		int checkpointSlot = this.paxosManager.getPaxosLogger().getCheckpointSlot(getPaxosID());
		StatePacket statePacket = (checkpointSlot>=syncReply.missingSlotNumbers.get(0) ? 
				StatePacket.getStatePacket(this.paxosManager.getPaxosLogger().getSlotBallotState(this.getPaxosID())) : null);
		if(statePacket!=null) log.info(this.getNodeState() + " sending checkpoint to node " + 
				syncReply.nodeID +": " + statePacket);
		else log.warning(this.getNodeState() + (!this.coordinator.exists() ? "[acceptor]" : 
			this.coordinator.isActive() ? "[coordinator]" : "[preactive-coordinator]" ) + 
			" has no state (yet) for " + syncReply);

		return statePacket!=null ? new MessagingTask(syncReply.nodeID, statePacket) : null;
	}

	/********************** End of failure detection and recovery methods *****************/

	/************************ Start of testing methods **************************/
	/* Used only to test paxos instance size. We really need a paxosManager
	 * to do anything real with paxos.
	 */
	private void testingNoRecovery() {
		int initSlot = 0;
		this.coordinator = new PaxosCoordinator();
		if(this.groupMembers[0]==this.getMyID()) coordinator.makeCoordinator(0, this.groupMembers[0], groupMembers, initSlot, true);
		this.paxosState = new PaxosAcceptor(0, this.groupMembers[0],initSlot,null);
	}

	protected void testingInit(int load) {
		this.coordinator.testingInitCoord(load);
		this.paxosState.testingInitInstance(load);
	}
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		System.out.println("FAILURE: I am not tested, so I am useless. Try running PaxosManager's test for now.");
	}

}
