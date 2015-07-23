package edu.umass.cs.utils;

import java.util.HashMap;

import edu.umass.cs.utils.Util;

/**
 * @author V. Arun
 */
public class DelayProfiler {
	private static HashMap<String, Double> averageMillis = new HashMap<String, Double>();
	private static HashMap<String, Double> averageNanos = new HashMap<String, Double>();
	private static HashMap<String, Double> averages = new HashMap<String, Double>();
	private static HashMap<String, Double> stdDevs = new HashMap<String, Double>();

	/**
	 * @param field
	 * @param map
	 * @return As specified by {@link HashMap#put(Object, Object)}/
	 */
	public synchronized static boolean register(String field,
			HashMap<String, Double> map) {
		if (map.containsKey(field))
			return false;
		map.put(field, 0.0);
		stdDevs.put(field, 0.0);
		return true;
	}

	/**
	 * @param field
	 * @param time
	 */
	public synchronized static void updateDelay(String field, double time) {
		long endTime = System.currentTimeMillis();
		register(field, averageMillis); // register if not registered
		double delay = averageMillis.get(field);
		delay = Util.movingAverage(endTime - time, delay);
		averageMillis.put(field, delay);
		// update deviation
		double dev = stdDevs.get(field);
		dev = Util.movingAverage(endTime - time - delay, dev);
		stdDevs.put(field, dev);
	}

	/**
	 * @param field
	 * @param time
	 */
	public synchronized static void updateDelayNano(String field, double time) {
		long endTime = System.nanoTime();
		register(field, averageNanos); // register if not registered
		double delay = averageNanos.get(field);
		delay = Util.movingAverage(endTime - time, delay);
		averageNanos.put(field, delay);
		// update deviation
		double dev = stdDevs.get(field);
		dev = Util.movingAverage(endTime - time - delay, dev);
		stdDevs.put(field, dev);
	}

	/**
	 * @param field
	 * @param time
	 * @param n
	 */
	public synchronized static void updateDelay(String field, long time, int n) {
		for (int i = 0; i < n; i++)
			updateDelay(field,
					System.currentTimeMillis()
							- (System.currentTimeMillis() - time) * 1.0 / n);
	}

	/**
	 * @param field
	 * @return The delay.
	 */
	public synchronized static double get(String field) {
		return averageMillis.containsKey(field) ? averageMillis.get(field)
				: 0.0;
	}

	/**
	 * @param field
	 * @param sample
	 */
	public synchronized static void updateMovAvg(String field, int sample) {
		register(field, averages); // register if not registered
		// update value
		double value = averages.get(field);
		value = Util.movingAverage(sample, value);
		averages.put(field, value);
		// update deviation
		double dev = stdDevs.get(field);
		dev = Util.movingAverage(sample - value, dev);
		stdDevs.put(field, dev);
	}

	/**
	 * @return Statistics as a string.
	 */
	public synchronized static String getStats() {
		String s = "[ ";
		s += statsHelper(averageMillis, "ms");
		s += statsHelper(averageNanos, "ns");
		s += statsHelper(averages, "");
		return s + " ]";
	}

	private static String statsHelper(HashMap<String, Double> map, String units) {
		int count = 0;
		String s = "";
		for (String field : map.keySet()) {
			s += ((count++ > 0 ? " | " : "") + field + ":"
					+ Util.df(map.get(field)) + "/"
					+ (stdDevs.get(field) > 0 ? "+" : "")
					+ Util.df(stdDevs.get(field)) + units);
		}
		return s;
	}
}