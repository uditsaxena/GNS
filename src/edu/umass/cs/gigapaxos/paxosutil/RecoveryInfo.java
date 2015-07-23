package edu.umass.cs.gigapaxos.paxosutil;

import java.util.Set;

import edu.umass.cs.utils.Util;

/**
 * @author V. Arun
 * @author arun
 *
 *         Utility class needed by PaxosManager and PaxosLogger for recovery.
 *         Just a container class.
 */
@SuppressWarnings("javadoc")
public class RecoveryInfo {
	final String paxosID;
	final int version;
	final Set<String> members;
	private String state = null;

	public RecoveryInfo(String id, int ver, String[] group) {
		this.paxosID = id;
		this.version = ver;
		this.members = Util.arrayOfObjectsToStringSet(group);
	}

	public RecoveryInfo(String id, int ver, String[] group, String state) {
		this.paxosID = id;
		this.version = ver;
		this.members = Util.arrayOfObjectsToStringSet(group);
		this.state = state;
	}

	public String getPaxosID() {
		return paxosID;
	}

	public int getVersion() {
		return version;
	}

	public Set<String> getMembers() {
		return members;
	}

	public String getState() {
		return this.state;
	}

	public String toString() {
		String s = "", group = "[";
		for (String member : this.members)
			group += member + " ";
		group += "]";
		s += "[ " + paxosID + ", " + version + ", " + group + ", " + this.state
				+ " ]";
		return s;
	}
}
