package edu.umass.cs.gns.nsdesign;

/**
 * Created by abhigyan on 3/27/14.
 */
public interface Reconfigurable extends Application {

	public boolean stopVersion(String name, short version); // getFinalState should return non-null value after this call

	public String getFinalState(String name, short version);

	public void putInitialState(String name, short version, String state);

	public int deleteFinalState(String name, short version);

}