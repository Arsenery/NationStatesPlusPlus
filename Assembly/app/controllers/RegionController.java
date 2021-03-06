package controllers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import net.nationstatesplusplus.assembly.util.DatabaseAccess;
import net.nationstatesplusplus.assembly.util.Utils;

import org.apache.commons.dbutils.DbUtils;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import org.joda.time.Duration;
import org.spout.cereal.config.ConfigurationNode;
import org.spout.cereal.config.yaml.YamlConfiguration;

import play.Logger;
import play.libs.Json;
import play.mvc.Result;
import play.mvc.Results;

import com.limewoodMedia.nsapi.NationStates;

public class RegionController extends NationStatesController {
	private final String imgurClientKey;
	public RegionController(DatabaseAccess access, YamlConfiguration config, NationStates api) {
		super(access, config, api);
		ConfigurationNode imgurAuth = getConfig().getChild("imgur");
		imgurClientKey = imgurAuth.getChild("client-key").getString(null);
	}

	public Result getUpdateTime(String region, double std) throws SQLException {
		Connection conn = null;
		int regionId = this.getDatabase().getRegionId(region);
		if (regionId == -1) {
			return Results.badRequest();
		}
		try {
			conn = getConnection();
			JsonNode updateTime = getUpdateTime(conn, regionId, std);
			Result result = Utils.handleDefaultGetHeaders(request(), response(), String.valueOf(updateTime.hashCode()), "60");
			if (result != null) {
				return result;
			}
			return ok(Json.toJson(updateTime)).as("application/json");
		} finally {
			DbUtils.closeQuietly(conn);
		}
	}

	private static int getUpdateOrder(Connection conn, int regionId) throws SQLException {
		try (PreparedStatement select = conn.prepareStatement("SELECT update_order FROM assembly.region WHERE id = ?")) {
			select.setInt(1, regionId);
			try (ResultSet set = select.executeQuery()) {
				if (set.next()) {
					return set.getInt(1);
				}
			}
		}
		return -1;
	}

	public static JsonNode getUpdateTime(Connection conn, int regionId, double std) throws SQLException {
		List<Long> majorData = new ArrayList<Long>(30);
		List<Long> minorData = new ArrayList<Long>(30);
		SummaryStatistics minor = new SummaryStatistics();
		SummaryStatistics major = new SummaryStatistics();
		
		final int updateOrder = getUpdateOrder(conn, regionId);

		try (PreparedStatement updateTime = conn.prepareStatement("SELECT normalized_start, major FROM assembly.region_update_calculations WHERE region = ? AND update_time < 200000 ORDER BY start DESC LIMIT 0, 28")) {
			updateTime.setInt(1, regionId);
			try (ResultSet times = updateTime.executeQuery()) {
				while(times.next()) {
					if (times.getInt(2) == 1) {
						major.addValue(times.getLong(1));
						majorData.add(times.getLong(1));
					} else {
						minor.addValue(times.getLong(1));
						minorData.add(times.getLong(1));
					}
				}
			}
		}
		boolean majorIsGuess = false;
		boolean minorIsGuess = false;

		//No data for major update, steal some from a nearby (in terms of update order) region we do have data for
		if (majorData.isEmpty() && updateOrder > -1) {
			majorIsGuess = true;
			//Find data for the "closest" region before us
			try (PreparedStatement select = conn.prepareStatement("SELECT region.id FROM assembly.region INNER JOIN assembly.region_update_calculations ON region.id = region_update_calculations.region WHERE region.alive = 1 AND region_update_calculations.start > ? AND major = 1 AND update_order BETWEEN ? AND ? ORDER BY update_order DESC LIMIT 0, 1")) {
				select.setLong(1, System.currentTimeMillis() - Duration.standardDays(8).getMillis());
				select.setInt(2, Math.max(0, updateOrder - 50));
				select.setInt(3, updateOrder);
				try (ResultSet set = select.executeQuery()) {
					if (set.next()) {
						int id = set.getInt(1);
						try (PreparedStatement updateTime = conn.prepareStatement("SELECT normalized_end FROM assembly.region_update_calculations WHERE region = ? AND major = 1 AND update_time < 200000 ORDER BY start DESC LIMIT 0, 14")) {
							updateTime.setInt(1, id);
							try (ResultSet times = updateTime.executeQuery()) {
								while(times.next()) {
									major.addValue(times.getLong(1));
									majorData.add(times.getLong(1));
								}
							}
						}
					}
				}
			}
			//Find data for the "closest" region after us
			try (PreparedStatement select = conn.prepareStatement("SELECT region.id FROM assembly.region INNER JOIN assembly.region_update_calculations ON region.id = region_update_calculations.region WHERE region.alive = 1 AND region_update_calculations.start > ? AND major = 1 AND update_order BETWEEN ? AND ? ORDER BY update_order DESC LIMIT 0, 1")) {
				select.setLong(1, System.currentTimeMillis() - Duration.standardDays(8).getMillis());
				select.setInt(2, updateOrder);
				select.setInt(3, updateOrder + 50);
				try (ResultSet set = select.executeQuery()) {
					if (set.next()) {
						int id = set.getInt(1);
						try (PreparedStatement updateTime = conn.prepareStatement("SELECT normalized_start FROM assembly.region_update_calculations WHERE region = ? AND major = 1 AND update_time < 200000 ORDER BY start DESC LIMIT 0, 14")) {
							updateTime.setInt(1, id);
							try (ResultSet times = updateTime.executeQuery()) {
								while(times.next()) {
									major.addValue(times.getLong(1));
									majorData.add(times.getLong(1));
								}
							}
						}
					}
				}
			}
		}

		//No data for minor update, steal some from a nearby (in terms of update order) region we do have data for
		if (minorData.isEmpty() && updateOrder > -1) {
			minorIsGuess = true;
			//Find data for the "closest" region before us
			try (PreparedStatement select = conn.prepareStatement("SELECT region.id FROM assembly.region INNER JOIN assembly.region_update_calculations ON region.id = region_update_calculations.region WHERE region.alive = 1 AND region_update_calculations.start > ? AND major = 0 AND update_order BETWEEN ? AND ? ORDER BY update_order DESC LIMIT 0, 1")) {
				select.setLong(1, System.currentTimeMillis() - Duration.standardDays(8).getMillis());
				select.setInt(2, Math.max(0, updateOrder - 50));
				select.setInt(3, updateOrder);
				try (ResultSet set = select.executeQuery()) {
					if (set.next()) {
						int id = set.getInt(1);
						try (PreparedStatement updateTime = conn.prepareStatement("SELECT normalized_end FROM assembly.region_update_calculations WHERE region = ? AND major = 1 AND update_time < 200000 ORDER BY start DESC LIMIT 0, 14")) {
							updateTime.setInt(1, id);
							try (ResultSet times = updateTime.executeQuery()) {
								while(times.next()) {
									minor.addValue(times.getLong(1));
									minorData.add(times.getLong(1));
								}
							}
						}
					}
				}
			}
			//Find data for the "closest" region after us
			try (PreparedStatement select = conn.prepareStatement("SELECT region.id FROM assembly.region INNER JOIN assembly.region_update_calculations ON region.id = region_update_calculations.region WHERE region.alive = 1 AND region_update_calculations.start > ? AND major = 0 AND update_order BETWEEN ? AND ? ORDER BY update_order DESC LIMIT 0, 1")) {
				select.setLong(1, System.currentTimeMillis() - Duration.standardDays(8).getMillis());
				select.setInt(2, updateOrder);
				select.setInt(3, updateOrder + 50);
				try (ResultSet set = select.executeQuery()) {
					if (set.next()) {
						int id = set.getInt(1);
						try (PreparedStatement updateTime = conn.prepareStatement("SELECT normalized_start FROM assembly.region_update_calculations WHERE region = ? AND major = 1 AND update_time < 200000 ORDER BY start DESC LIMIT 0, 14")) {
							updateTime.setInt(1, id);
							try (ResultSet times = updateTime.executeQuery()) {
								while(times.next()) {
									minor.addValue(times.getLong(1));
									minorData.add(times.getLong(1));
								}
							}
						}
					}
				}
			}
		}

		if (majorData.size() > 0) {
			if (std > 0) {
				//Check for major update outliers
				Set<Long> outliers = new HashSet<Long>();
				for (Long time : majorData) {
					if (time.longValue() > (major.getMean() + major.getStandardDeviation() * std)) {
						outliers.add(time);
					}
				}
				if (outliers.size() > 0) {
					major.clear();
					for (Long time : majorData) {
						if (!outliers.contains(time)) {
							major.addValue(time);
						}
					}
				}
			}
		}
		if (minorData.size() > 0) {
			Set<Long> outliers = new HashSet<Long>();
			//Check for minor update outliers
			for (Long time : minorData) {
				if (time.longValue() > (minor.getMean() + minor.getStandardDeviation() * std)) {
					outliers.add(time);
				}
			}
			if (outliers.size() > 0) {
				minor.clear();
				for (Long time : minorData) {
					if (!outliers.contains(time)) {
						minor.addValue(time);
					}
				}
			}
		}
		
		int totalRegions;
		try (PreparedStatement select = conn.prepareStatement("SELECT count(id) FROM assembly.region WHERE alive = 1 AND update_order > -1")) {
			try (ResultSet set = select.executeQuery()) {
				set.next();
				totalRegions = set.getInt(1);
			}
		}
		
		Map<String, Object> data = new HashMap<String, Object>();
		Map<String, Object> majorUpdate = new HashMap<String, Object>();
		majorUpdate.put("mean", Double.valueOf(major.getMean()).longValue());
		majorUpdate.put("std", Double.valueOf(major.getStandardDeviation()).longValue());
		majorUpdate.put("max", Double.valueOf(major.getMax()).longValue());
		majorUpdate.put("min", Double.valueOf(major.getMin()).longValue());
		majorUpdate.put("geomean", Double.valueOf(major.getGeometricMean()).longValue());
		majorUpdate.put("variance", Double.valueOf(major.getVariance()).longValue());
		majorUpdate.put("isGuess", majorIsGuess);
		majorUpdate.put("update_order", updateOrder + 1);
		majorUpdate.put("total_regions", totalRegions);
		data.put("major", majorUpdate);
		
		Map<String, Object> minorUpdate = new HashMap<String, Object>();
		minorUpdate.put("mean", Double.valueOf(minor.getMean()).longValue());
		minorUpdate.put("std", Double.valueOf(minor.getStandardDeviation()).longValue());
		minorUpdate.put("max", Double.valueOf(minor.getMax()).longValue());
		minorUpdate.put("min", Double.valueOf(minor.getMin()).longValue());
		minorUpdate.put("geomean", Double.valueOf(minor.getGeometricMean()).longValue());
		minorUpdate.put("variance", Double.valueOf(minor.getVariance()).longValue());
		minorUpdate.put("isGuess", minorIsGuess);
		minorUpdate.put("update_order", updateOrder + 1);
		minorUpdate.put("total_regions", totalRegions);
		data.put("minor", minorUpdate);
		return Json.toJson(data);
	}

	public static JsonNode getRecordPopulation(Connection conn, String region) throws SQLException {
		PreparedStatement population = conn.prepareStatement("SELECT population, timestamp FROM assembly.region_populations WHERE region = ? ORDER BY population DESC LIMIT 0, 1;");
		population.setString(1, Utils.sanitizeName(region));
		ResultSet result = population.executeQuery();
		HashMap<String, Object> data = new HashMap<String, Object>();
		if (result.next()) {
			data.put("population", result.getInt(1));
			data.put("region", region);
			data.put("timestamp", result.getLong(2));
		} else {
			data.put("population", -1);
			data.put("region", region);
			data.put("timestamp", 0);
		}
		return Json.toJson(data);
	}

	public Result getRegionSummary(String region) throws SQLException {
		List<Map<String, Object>> regionData = new ArrayList<Map<String, Object>>();
		if (!region.isEmpty() && getDatabase().getRegionId(region) != -1) {
			try (Connection conn = getConnection()) {
				try (PreparedStatement statement = conn.prepareStatement("SELECT name, title, flag, influence, wa_member FROM assembly.nation WHERE alive = 1 AND region = ? ORDER BY update_order ASC")) {
					statement.setInt(1, getDatabase().getRegionId(region));
					try (ResultSet result = statement.executeQuery()) {
						while(result.next()) {
							Map<String, Object> nation = new HashMap<String, Object>();
							nation.put("name", result.getString(1));
							nation.put("title", result.getString(2));
							nation.put("flag", result.getString(3));
							nation.put("influence", result.getInt(4));
							nation.put("wa_member", result.getByte(5) == 1);
							regionData.add(nation);
						}
					}
				}
			}
		}
		Result r = Utils.handleDefaultGetHeaders(request(), response(), String.valueOf(regionData.hashCode()), "6");
		if (r != null) {
			return r;
		}
		return ok(Json.toJson(regionData)).as("application/json");
	}

	public static JsonNode getEmbassies(Connection conn, String region, int limit) throws SQLException {
		if (limit <= 0) {
			limit = Integer.MAX_VALUE;
		}
		List<Map<String, String>> embassies = new ArrayList<Map<String, String>>();
		PreparedStatement statement = conn.prepareStatement("SELECT embassies FROM assembly.region WHERE name = ?");
		statement.setString(1, Utils.sanitizeName(region));
		ResultSet result = statement.executeQuery();
		if (result.next()) {
			String list = result.getString(1);
			if (!result.wasNull() && list != null && !list.isEmpty()) {
				String[] split = list.split(":");
				for (int i = 0; i < Math.min(limit, split.length); i++) {
					Map<String, String> regionData = new HashMap<String, String>();
					regionData.put("name", split[i]);
					regionData.put("flag", Utils.getRegionFlag(split[i], conn));
					embassies.add(regionData);
				}
			}
		}
		DbUtils.closeQuietly(result);
		DbUtils.closeQuietly(statement);
		return Json.toJson(embassies);
	}

	public Result getEmbassies(String region) throws SQLException {
		JsonNode data;
		Connection conn = null;
		try {
			conn = getConnection();
			data = getEmbassies(conn, region, -1);
		} finally {
			DbUtils.closeQuietly(conn);
		}

		Result r = Utils.handleDefaultGetHeaders(request(), response(), String.valueOf(data.hashCode()), "3600");
		if (r != null) {
			return r;
		}
		return ok(data).as("application/json");
	}

	public Result getNations(String regions, boolean xml) throws SQLException {
		Map<String, Object> regionData = new LinkedHashMap<String, Object>();
		Connection conn = null;
		try {
			conn = getConnection();
			String[] split = regions.split(",");
			for (int i = 0; i < split.length; i++) {
				List<String> nations = new ArrayList<String>();
				int regionId = getDatabase().getRegionId(split[i]);
				if (regionId != -1) {
					PreparedStatement statement = conn.prepareStatement("SELECT name FROM assembly.nation WHERE alive = 1 AND region = ? ORDER BY update_order ASC");
					statement.setInt(1, regionId);
					ResultSet result = statement.executeQuery();
					while(result.next()) {
						nations.add(result.getString(1));
					}
					Collections.reverse(nations);
					DbUtils.closeQuietly(result);
					DbUtils.closeQuietly(statement);
				}
				regionData.put(split[i], nations);
			}
		} finally {
			DbUtils.closeQuietly(conn);
		}

		Utils.handleDefaultPostHeaders(request(), response());
		if (xml) {
			String data = "<REGIONS>\n\t";
			for (Entry<String, Object> e : regionData.entrySet()) {
				data += "<REGION id=\"" + e.getKey() + "\">\n\t\t<NATIONS>";
				@SuppressWarnings("unchecked")
				List<String> nations = (List<String>) e.getValue();
				StringBuilder b = new StringBuilder();
				int chunk = 0;
				for (String nation : nations) {
					if (b.length() / 30000 != chunk) {
						chunk++;
						b.append("</NATIONS><NATIONS>");
					} else if (b.length() > 0) {
						b.append(":");
					}
					b.append(nation);
				}
				b.append("</NATIONS>\n\t</REGION>");
				data += b.toString();
			}
			data += "\n</REGIONS>";
			return ok(data).as("application/xml");
		} else {
			return ok(Json.toJson(regionData.size() == 1 ? regionData.get(regions.split(",")[0]) : regionData)).as("application/json");
		}
	}

	public Result getPopulationTrends(String region) throws SQLException {
		Map<String, Object> data = new HashMap<String, Object>(4);
		Connection conn = null; 
		try {
			conn = getConnection();
			PreparedStatement statement = conn.prepareStatement("SELECT population, timestamp FROM assembly.region_populations WHERE region = ? AND timestamp > ? ORDER BY TIMESTAMP DESC");
			statement.setString(1, Utils.sanitizeName(region));
			statement.setLong(2, System.currentTimeMillis() - Duration.standardDays(30).getMillis());
			ResultSet result = statement.executeQuery();
			List<Population> population = new ArrayList<Population>();
			while(result.next()) {
				population.add(new Population(result.getInt(1), result.getLong(2)));
			}
			data.put("region", population);
			
			DbUtils.closeQuietly(result);
			DbUtils.closeQuietly(statement);
		} finally {
			DbUtils.closeQuietly(conn);
		}

		Result result = Utils.handleDefaultGetHeaders(request(), response(), String.valueOf(data.hashCode()), "21600");
		if (result != null) {
			return result;
		}
		return ok(Json.toJson(data)).as("application/json");
	}

	static class Population {
		@JsonProperty
		int population;
		@JsonProperty
		long timestamp;
		Population() {
			
		}

		Population(int population, long timestamp) {
			this.population = population;
			this.timestamp = timestamp;
		}
	}

	public Result setRegionalMap(String region, boolean disband) throws SQLException {
		Result ret = Utils.validateRequest(request(), response(), getAPI(), getDatabase());
		if (ret != null) {
			return ret;
		}
		String nation = Utils.sanitizeName(Utils.getPostValue(request(), "nation"));
		
		Utils.handleDefaultPostHeaders(request(), response());
		Connection conn = null;
		try {
			conn = getConnection();
			PreparedStatement select = conn.prepareStatement("SELECT delegate, founder FROM assembly.region WHERE name = ?");
			select.setString(1, Utils.sanitizeName(region));
			ResultSet result = select.executeQuery();
			boolean regionAdministrator = true;
			if (result.next()) {
				Logger.info("Attempting to set map for " + region + ", nation: " + nation);
				Logger.info("Delegate: " + result.getString(1) + " | Founder: " + result.getString(2));
				if (!nation.equals(result.getString(1)) && !nation.equals(result.getString(2))) {
					regionAdministrator = false;
				}
			} else {
				Logger.info("Attempting to set map for " + region + ", no region found!");
				regionAdministrator = false;
			}
			
			DbUtils.closeQuietly(result);
			DbUtils.closeQuietly(select);
			
			if (!regionAdministrator) {
				return Results.unauthorized();
			}
			
			if (disband) {
				PreparedStatement update = conn.prepareStatement("UPDATE assembly.region SET regional_map = NULL, regional_map_preview = NULL WHERE name = ?");
				update.setString(1, Utils.sanitizeName(region));
				update.executeUpdate();
				DbUtils.closeQuietly(update);
			} else {
				String mapLink = Utils.getPostValue(request(), "regional_map");
				String mapPreview = Utils.getPostValue(request(), "regional_map_preview");
				if (mapPreview == null) {
					return Results.badRequest("missing map preview");
				}
				if (mapLink == null) {
					return Results.badRequest("missing map link");
				}
				String imgurPreview = null;
				try {
					imgurPreview = Utils.uploadToImgur(mapPreview, null, imgurClientKey);
				} catch (Exception e) {
					Logger.warn("Unable to upload image to imgur for regional map [" + mapPreview + "]", e);
				}
				if (imgurPreview == null) {
					return Results.badRequest("invalid map preview");
				}
				PreparedStatement update = conn.prepareStatement("UPDATE assembly.region SET regional_map = ?, regional_map_preview = ? WHERE name = ?");
				update.setString(1, mapLink);
				update.setString(2, imgurPreview);
				update.setString(3, Utils.sanitizeName(region));
				update.executeUpdate();
				DbUtils.closeQuietly(update);
			}
		} finally {
			DbUtils.closeQuietly(conn);
		}
		return Results.ok();
	}

	public static JsonNode getRegionalMap(Connection conn, String region) throws SQLException {
		Map<String, String> links = new HashMap<String, String>(3);
		PreparedStatement update = conn.prepareStatement("SELECT regional_map, regional_map_preview FROM assembly.region WHERE name = ?");
		update.setString(1, Utils.sanitizeName(region));
		ResultSet set = update.executeQuery();
		if (set.next()) {
			String mapLink = set.getString(1);
			if (!set.wasNull()) {
				links.put("regional_map", mapLink);
			}
			String mapPreview = set.getString(2);
			if (!set.wasNull()) {
				links.put("regional_map_preview", mapPreview);
			}
		}
		DbUtils.closeQuietly(set);
		DbUtils.closeQuietly(update);
		return Json.toJson(links);
	}

	public Result getRegionalMap(String region) throws SQLException {
		Connection conn = null;
		try {
			conn = getConnection();
			JsonNode map = getRegionalMap(conn, region);
			Result result = Utils.handleDefaultGetHeaders(request(), response(), String.valueOf(map.hashCode()), "60");
			if (result != null) {
				return result;
			}
			return Results.ok(Json.toJson(map)).as("application/json");
		} finally {
			DbUtils.closeQuietly(conn);
		}
	}

	public Result setRegionalTitle(String region, boolean disband) throws SQLException {
		Result ret = Utils.validateRequest(request(), response(), getAPI(), getDatabase());
		if (ret != null) {
			return ret;
		}
		String nation = Utils.sanitizeName(Utils.getPostValue(request(), "nation"));
		String delegateTitle = Utils.getPostValue(request(), "delegate_title");
		String founderTitle = Utils.getPostValue(request(), "founder_title");
		Utils.handleDefaultPostHeaders(request(), response());

		//Must have valid title
		if (!disband) {
			if (delegateTitle == null || founderTitle == null || delegateTitle.isEmpty() || founderTitle.isEmpty()) {
				return Results.badRequest("Missing title");
			} else if (delegateTitle.length() > 40 || founderTitle.length() > 40) {
				return Results.badRequest("Maximum title length is 40 characters");
			}
		}

		Connection conn = null;
		try {
			conn = getConnection();
			PreparedStatement select = conn.prepareStatement("SELECT id, delegate, founder FROM assembly.region WHERE name = ?");
			select.setString(1, Utils.sanitizeName(region));
			ResultSet result = select.executeQuery();
			boolean regionAdministrator = true;
			int regionId = -1;
			if (result.next()) {
				regionId = result.getInt(1);
				final String delegate = result.getString(2);
				final String founder = result.getString(3);
				Logger.info("Attempting to set regional titles for " + region + ", nation: " + nation);
				Logger.info("Delegate: " + delegate + " | Founder: " + founder);
				if (!nation.equals(delegate) && !nation.equals(founder)) {
					regionAdministrator = false;
				}
			} else {
				Logger.info("Attempting to set regional titles for " + region + ", no region found!");
				regionAdministrator = false;
			}
			if (regionAdministrator) {
				PreparedStatement update = conn.prepareStatement("UPDATE assembly.region SET delegate_title = ?, founder_title = ? WHERE id = ?");
				if (!disband) {
					update.setString(1, delegateTitle);
					update.setString(2, founderTitle);
				} else {
					update.setNull(1, Types.VARCHAR);
					update.setNull(2, Types.VARCHAR);
				}
				update.setInt(3, regionId);
				update.executeUpdate();
				return Results.ok();
			}
		} finally {
			DbUtils.closeQuietly(conn);
		}
		return Results.unauthorized();
	}

	public static JsonNode getRegionalTitles(Connection conn, String region) throws SQLException {
		Map<String, String> data = new HashMap<String, String>(2);
		PreparedStatement select = conn.prepareStatement("SELECT delegate_title, founder_title FROM assembly.region WHERE name = ?");
		select.setString(1, Utils.sanitizeName(region));
		ResultSet result = select.executeQuery();
		if (result.next()) {
			String title = result.getString(1);
			if (!result.wasNull()) {
				data.put("delegate_title", title);
			}
			title = result.getString(2);
			if (!result.wasNull()) {
				data.put("founder_title", title);
			}
		}
		return Json.toJson(data);
	}

	public Result getRegionalTitles(String region) throws SQLException {
		Connection conn = null;
		try {
			conn = getConnection();
			JsonNode titles = getRegionalTitles(conn, region);
			Result r = Utils.handleDefaultGetHeaders(request(), response(), String.valueOf(titles.hashCode()), "600");
			if (r != null) {
				return r;
			}
			return Results.ok(titles).as("application/json");
		} finally {
			DbUtils.closeQuietly(conn);
		}
	}
}
