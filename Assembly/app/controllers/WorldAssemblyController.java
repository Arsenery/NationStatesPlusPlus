package controllers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.apache.commons.dbutils.DbUtils;
import org.spout.cereal.config.yaml.YamlConfiguration;

import play.libs.Json;
import play.mvc.Result;

import com.afforess.assembly.util.DatabaseAccess;
import com.afforess.assembly.util.Utils;

public class WorldAssemblyController extends DatabaseController {

	public WorldAssemblyController(DatabaseAccess access, YamlConfiguration config) {
		super(access, config);
	}

	public Result getWAMembers(String region) throws SQLException, ExecutionException {
		Map<String, Map<String, Object>> json = new HashMap<String, Map<String, Object>>();
		Connection conn = null; 
		try {
			conn = getConnection();
			PreparedStatement statement = conn.prepareStatement("SELECT title, influence, influence_desc, count(e.endorsed) AS endorsements from assembly.nation AS n LEFT OUTER JOIN assembly.endorsements AS e ON n.id = e.endorsed WHERE wa_member = 1 AND region = ? GROUP BY title;");
			statement.setInt(1, getDatabase().getRegionIdCache().get(Utils.sanitizeName(region)));
			ResultSet result = statement.executeQuery();
			while(result.next()) {
				String nation = result.getString(1);
				HashMap<String, Object> values = new HashMap<String, Object>();
				values.put("endorsements", result.getInt(4));
				values.put("influence", result.getInt(2));
				values.put("influence_desc", result.getString(3));
				json.put(nation, values);
			}
		} finally {
			DbUtils.closeQuietly(conn);
		}

		Result result = Utils.handleDefaultGetHeaders(request(), response(), String.valueOf(json.hashCode()));
		if (result != null) {
			return result;
		}
		return ok(Json.toJson(json)).as("application/json");
	}

	public Result getEndorsements(String name) throws SQLException, ExecutionException {
		List<String> nations = new ArrayList<String>();
		Connection conn = null; 
		try {
			conn = getConnection();
			PreparedStatement statement = conn.prepareStatement("SELECT n.title FROM assembly.nation AS n LEFT OUTER JOIN assembly.endorsements AS e ON n.id = e.endorsed WHERE e.endorser = ?");
			statement.setInt(1, getDatabase().getNationIdCache().get(Utils.sanitizeName(name)));
			ResultSet result = statement.executeQuery();
			while(result.next()) {
				String title = result.getString(1);
				nations.add(title);
			}
		} finally {
			DbUtils.closeQuietly(conn);
		}

		Result result = Utils.handleDefaultGetHeaders(request(), response(), String.valueOf(nations.hashCode()));
		if (result != null) {
			return result;
		}
		return ok(Json.toJson(nations)).as("application/json");
	}

}
