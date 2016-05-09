/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */

package org.archicontribs.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;

import com.archimatetool.model.util.Logger;

/**
 * Database Model Exporter
 * 
 * @author Herve Jouin
 *
 * 
 * v0.1 : 25/03/2016		plugin creation
 * v0.2 : 01/05/2016		Add models versionning
 * v0.3 : 08/05/2016		Add import filtering
 */
public class DBPlugin {
	public static String pluginVersion = "0.3";
	public static String pluginName = "DatabasePlugin";
	public static String pluginTitle = "Database import/export plugin v" + pluginVersion;
	public static String Separator = "-";

	public enum Level { Info, Warning, Error };

	public static void popup(Level level, String msg) {
		popup(level,msg,null);
	}
	public static void popup(Level level, String msg, Exception e) {
		String msg2 = msg;
		if ( e != null) msg2 += "\n\n" + e.getMessage();

		switch ( level ) {
		case Info :
			//Logger.logInfo(msg, e);
			MessageDialog.openInformation(Display.getDefault().getActiveShell(), pluginTitle, msg2);
			break;
		case Warning :
			Logger.logWarning(msg, e);
			MessageDialog.openWarning(Display.getDefault().getActiveShell(), pluginTitle, msg2);
			break;
		case Error :
			Logger.logError(msg, e);
			MessageDialog.openError(Display.getDefault().getActiveShell(), pluginTitle, msg2);
			break;
		}
	}
	public static void sql(Connection db, String request, String... parameters) throws SQLException {
		PreparedStatement pstmt = db.prepareStatement(request);
		for (int rank=0 ; rank < parameters.length ; rank++)
			pstmt.setString(rank+1, parameters[rank]);
		pstmt.executeUpdate();
		pstmt.close();
	}
	public static ResultSet select(Connection db, String request, String... parameters) throws SQLException {
		PreparedStatement pstmt = db.prepareStatement(request, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.TYPE_SCROLL_INSENSITIVE);
		for (int rank=0 ; rank < min(parameters.length, count(request, '?')) ; rank++)
			pstmt.setString(rank+1, parameters[rank]);
		return pstmt.executeQuery();
	}
	public static int count(String _string, char _c)
	{
	    int count = 0;
	    for (int i=0; i < _string.length(); i++)
	        if (_string.charAt(i) == _c) count++;
	    return count;
	}
	public static int max(int _a, int _b) {
		return _a > _b ? _a : _b;
	}
	public static int min(int _a, int _b) {
		return _a < _b ? _a : _b;
	}
	@SafeVarargs
	public static final <T> void update(Connection db, String request, T...args) throws SQLException {
		for (int rank=0 ; rank < args.length ; rank++) request += rank == 0 ? " VALUES (?" : ",?";
		request += ")";

		PreparedStatement pstmt = db.prepareStatement(request);
		for (int rank=0 ; rank < args.length ; rank++) {
			if ( args[rank] == null ) {
				pstmt.setString(rank+1, null);
			} else {
				if ( args[rank] instanceof String )
					pstmt.setString(rank+1, (String)args[rank]);
				else
					pstmt.setInt(rank+1, (int)args[rank]);
			}
		}
		pstmt.executeUpdate();
		pstmt.close();
	}
	public static String incMinor(String _version) {
		if ( _version != null ) {
			String version[] = _version.split("\\.");
			return version[0] + "." + String.valueOf(Integer.valueOf(version[1])+1);
		}
		return "0.1";
	}
	public static String incMajor(String _version) {
		if ( _version != null ) {
			String version[] = _version.split("\\.");
			return String.valueOf(Integer.valueOf(version[0])+1) + ".0";
		}
		return "1.0";
	}
	
    public static String capitalize(String phrase) {
        if (phrase.isEmpty()) return phrase;
        StringBuilder result = new StringBuilder();
        for ( String s: phrase.split(" ") )
        	result.append(s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase());
        return result.toString();
    }
}