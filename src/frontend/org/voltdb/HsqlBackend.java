/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.voltdb.catalog.CatalogMap;
import org.voltdb.catalog.StmtParameter;
import org.voltdb.exceptions.ConstraintFailureException;
import org.voltdb.types.TimestampType;
import org.voltdb.utils.CatalogUtil;
import org.voltdb.utils.LogKeys;
import org.voltdb.utils.VoltLoggerFactory;

/**
 * A wrapper around the HSQLDB engine. This class can be used to execute SQL
 * statements instead of the C++ ExecutionEngine. It is currently not used.
 *
 */
public class HsqlBackend {
    /** java.util.logging logger. */
    @SuppressWarnings("unused")
    private static final Logger log = Logger.getLogger(HsqlBackend.class.getName(), VoltLoggerFactory.instance());
    private static final Logger hostLog = Logger.getLogger("HOST", VoltLoggerFactory.instance());
    private static final Logger sqlLog = Logger.getLogger("SQL", VoltLoggerFactory.instance());

    Connection dbconn;

    public HsqlBackend(int siteId) {
        try {
            Class.forName("org.hsqldb.jdbcDriver" );
        } catch (Exception e) {
            throw new RuntimeException("Failed to load HSQLDB JDBC driver.", e);
        }

        try {
            final String connectionURL = "jdbc:hsqldb:mem:x" + String.valueOf(siteId);
            dbconn = DriverManager.getConnection(connectionURL, "sa", "");
            dbconn.setAutoCommit(true);
            dbconn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open connection to " + "jdbc:hsqldb:mem:x" + String.valueOf(siteId), e);
        }
    }

    /** Creates a new backend wrapping dbconn. This is used for testing only. */
    public HsqlBackend(Connection dbconn) {
        this.dbconn = dbconn;
    }

    public void runDDL(String ddl) {
        try {
//            System.err.println("Executing " + ddl);
            Statement stmt = dbconn.createStatement();
            /*boolean success =*/ stmt.execute(ddl);
            SQLWarning warn = stmt.getWarnings();
            if (warn != null)
                sqlLog.warn(warn.getMessage());
            //LOG.info("SQL DDL execute result: " + (success ? "true" : "false"));
        } catch (SQLException e) {
            // IGNORE??? hostLog.l7dlog( Level.ERROR, LogKeys.host_Backend_RunDDLFailed.name(), new Object[] { ddl }, e);
        }

    }

    public VoltTable runDML(String dml) {
        dml = dml.trim();
        String indicator = dml.substring(0, 6).toLowerCase();
        if (indicator.equals("select")) {
            try {
                Statement stmt = dbconn.createStatement();
                sqlLog.l7dlog( Level.DEBUG, LogKeys.sql_Backend_ExecutingDML.name(), new Object[] { dml }, null);
                sqlLog.debug("Executing " + dml);
                ResultSet rs = stmt.executeQuery(dml);
                ResultSetMetaData rsmd = rs.getMetaData();

                // note the index values here carefully
                VoltTable.ColumnInfo[] columns = new VoltTable.ColumnInfo[rsmd.getColumnCount()];
                for (int i = 1; i <= rsmd.getColumnCount(); i++) {
                    String colname = rsmd.getColumnLabel(i);
                    String type = rsmd.getColumnTypeName(i);
                    //LOG.fine("Column type: " + type);
                    if (type.equals("VARCHAR"))
                        columns[i-1] = new VoltTable.ColumnInfo(colname, VoltType.STRING);
                    else if (type.equals("TINYINT"))
                        columns[i-1] = new VoltTable.ColumnInfo(colname, VoltType.TINYINT);
                    else if (type.equals("SMALLINT"))
                        columns[i-1] = new VoltTable.ColumnInfo(colname, VoltType.SMALLINT);
                    else if (type.equals("INTEGER"))
                        columns[i-1] = new VoltTable.ColumnInfo(colname, VoltType.INTEGER);
                    else if (type.equals("BIGINT"))
                        columns[i-1] = new VoltTable.ColumnInfo(colname, VoltType.BIGINT);
                    else if (type.equals("DECIMAL"))
                        columns[i-1] = new VoltTable.ColumnInfo(colname, VoltType.DECIMAL);
                    else if (type.equals("FLOAT"))
                        columns[i-1] = new VoltTable.ColumnInfo(colname, VoltType.FLOAT);
                    else if (type.equals("TIMESTAMP"))
                        columns[i-1] = new VoltTable.ColumnInfo(colname, VoltType.TIMESTAMP);
                    else
                        throw new ExpectedProcedureException("Trying to create a column in Backend with a (currently) unsupported type: " + type);
                }
                VoltTable table = new VoltTable(columns);
                while (rs.next()) {
                    Object[] row = new Object[table.getColumnCount()];
                    for (int i = 0; i < table.getColumnCount(); i++) {
                        // TODO(evanj): JDBC returns 0 instead of null. Put null into the row?
                        if (table.getColumnType(i) == VoltType.STRING)
                            row[i] = rs.getString(i + 1);
                        else if (table.getColumnType(i) == VoltType.TINYINT)
                            row[i] = rs.getByte(i + 1);
                        else if (table.getColumnType(i) == VoltType.SMALLINT)
                            row[i] = rs.getShort(i + 1);
                        else if (table.getColumnType(i) == VoltType.INTEGER)
                            row[i] = rs.getInt(i + 1);
                        else if (table.getColumnType(i) == VoltType.BIGINT)
                            row[i] = rs.getLong(i + 1);
                        else if (table.getColumnType(i) == VoltType.DECIMAL)
                            row[i] = rs.getBigDecimal(i + 1);
                        else if (table.getColumnType(i) == VoltType.FLOAT)
                            row[i] = rs.getDouble(i + 1);
                        else if (table.getColumnType(i) == VoltType.TIMESTAMP) {
                            Timestamp t = rs.getTimestamp(i + 1);
                            if (t == null) {
                                row[i] = null;
                            } else {
                                // convert from millisecond to microsecond granularity
                                row[i] = new org.voltdb.types.TimestampType(t.getTime() * 1000);
                            }
                        } else {
                            throw new ExpectedProcedureException("Trying to read a (currently) unsupported type from a JDBC resultset.");
                        }
                    }
                    table.addRow(row);
                }
                stmt.close();
                rs.close();
                return table;
            } catch (Exception e) {
                if (e instanceof ExpectedProcedureException) {
                    throw (ExpectedProcedureException)e;
                }
                sqlLog.l7dlog( Level.TRACE, LogKeys.sql_Backend_DmlError.name(), e);
                throw new ExpectedProcedureException("HSQLDB Backend DML Error ", e);
            }
        }
        else {
            try {
                //System.out.println("Creating statements");
                Statement stmt = dbconn.createStatement();
                //System.out.println("Created statement");
                sqlLog.debug("Executing: " + dml);
                long ucount = stmt.executeUpdate(dml);
                //System.out.println("got long back");
                sqlLog.debug("  result: " + String.valueOf(ucount));
                VoltTable table = new VoltTable(new VoltTable.ColumnInfo("", VoltType.BIGINT));
                //System.out.println("table");
                table.addRow(ucount);
                //System.out.println("row added");
                return table;
            } catch(SQLException e) {
                // glorious hack to determine if the error is a constraint failure
                if (e.getMessage().contains("constraint")) {
                    sqlLog.l7dlog( Level.TRACE, LogKeys.sql_Backend_ConvertingHSQLExtoCFEx.name(), e);
                    final byte messageBytes[] = e.getMessage().getBytes();
                    ByteBuffer b = ByteBuffer.allocate(21 + messageBytes.length);
                    b.putInt(messageBytes.length);
                    b.put(messageBytes);
                    b.put(e.getSQLState().getBytes());
                    b.putLong(0);//Move forward 8 bytes to provide header info for CFE, ConstraintType, TableId
                    b.putInt(0);//Table size is 0
                    b.rewind();
                    throw new ConstraintFailureException(b);
                }
                else {
                    sqlLog.l7dlog( Level.TRACE, LogKeys.sql_Backend_DmlError.name(), e);
                    throw new ExpectedProcedureException("HSQLDB Backend DML Error ", e);
                }

            } catch (Exception e) {
                // rethrow an expected exception
                sqlLog.l7dlog( Level.TRACE, LogKeys.sql_Backend_DmlError.name(), e);
                throw new ExpectedProcedureException("HSQLDB Backend DML Error ", e);
            }
        }
    }

    VoltTable runSQLWithSubString(String stmt, Object... args){
        StringBuilder sqlOut =  new StringBuilder(stmt.length() * 2);
       
        String[] words = stmt.split(" ");
        
        int totalQuestionMarks = 0;
        for(int i = 0; i < words.length; i++){
            if(words[i].contains("?")){
                totalQuestionMarks++;
            }
        }
        //System.out.println(totalQuestionMarks);
        //System.out.println(args.length);
        /**
         * TODO: Find a better way of handling errors exit or something
         */
        if(totalQuestionMarks > args.length){
            System.out.println("Error not all params provided");
        } else if (totalQuestionMarks < args.length){
            System.out.println("Error: too many params provided");
        }
        
        int argsTracker = 0;
        for(int i = 0; i < words.length; i++){
            if (words[i].contains("?")){
                if(words[i].equals("?")){
                   // String insert = getInsertVal(args[argsTracker]);
                    sqlOut.append(getInsertVal(args[argsTracker]) + " ");
                    argsTracker++;
                } else if (words[i].length() > 1) {
                    for(int j = 0; j < words[i].length(); j++){
                        if(words[i].charAt(j) == '?'){
                            //String insert = getInsertVal(args[argsTracker]);
                            sqlOut.append(getInsertVal(args[argsTracker]));
                            argsTracker++;
                        } else {
                            sqlOut.append(words[i].charAt(j));
                        }      
                    }
                    sqlOut.append(" ");
                }
            } else {
                sqlOut.append(words[i] + " ");
            }
           
        }
       // sqlOut.append(";");
        //System.out.println(sqlOut);
        //System.out.println(sqlOut.toString());
       VoltTable result = runDML(sqlOut.toString());
       
       return result;
        
    }
    
    private Object getInsertVal(Object arg){
        /**
         * TODO: finish testing to make sure it works
         */
        String temp = "";
        if(arg instanceof String){
            temp = "'" + arg + "'";
            return temp;
        } else {
            return arg;
        }
        
    }
    VoltTable runSQLWithSubstitutions(final SQLStmt stmt, Object... args) {
        //HSQLProcedureWrapper does nothing smart. it just implements this interface with runStatement()
        StringBuilder sqlOut = new StringBuilder(stmt.getText().length() * 2);

        System.out.println(stmt.catStmt);
        CatalogMap<StmtParameter> sparamsMap = stmt.catStmt.getParameters();
        List<StmtParameter> sparams = CatalogUtil.getSortedCatalogItems(sparamsMap, "index");
        assert(sparams != null);

        int lastIndex = 0;
        String sql = stmt.getText();
        for (int i = 0; i < args.length; i++) {
            int nextIndex = sql.indexOf('?', lastIndex);
            if (nextIndex == -1)
                throw new RuntimeException("SQL Statement has more arguments than params.");
            sqlOut.append(sql, lastIndex, nextIndex);
            lastIndex = nextIndex + 1;

            StmtParameter stmtParam = sparams.get(i);
            assert(stmtParam != null);
            VoltType type = VoltType.get((byte) stmtParam.getJavatype());

            if (args[i] == null) {
                sqlOut.append("NULL");
            } else if (args[i] instanceof TimestampType) {
                if (type != VoltType.TIMESTAMP)
                    throw new RuntimeException("Inserting date into mismatched column type in HSQL.");
                TimestampType d = (TimestampType) args[i];
                // convert VoltDB's microsecond granularity to millis.
                Timestamp t = new Timestamp(d.getTime() * 1000);
                sqlOut.append('\'').append(t.toString()).append('\'');
            } else if (args[i] instanceof byte[]) {
                if (type != VoltType.STRING)
                    throw new RuntimeException("Inserting string (bytes) into mismatched column type in HSQL.");
                // Convert from byte[] -> String; escape single quotes
                sqlOut.append(sqlEscape(new String((byte[]) args[i])));
            } else if (args[i] instanceof String) {
                if (type != VoltType.STRING)
                    throw new RuntimeException("Inserting string into mismatched column type in HSQL.");
                // Escape single quotes
                sqlOut.append(sqlEscape((String) args[i]));
            } else {
                if (type == VoltType.TIMESTAMP) {
                    long t = Long.parseLong(args[i].toString());
                    TimestampType d = new TimestampType(t);
                    // convert VoltDB's microsecond granularity to millis
                    Timestamp ts = new Timestamp(d.getTime() * 1000);
                    sqlOut.append('\'').append(ts.toString()).append('\'');
                }
                else
                    sqlOut.append(args[i].toString());
            }
        }
        sqlOut.append(sql, lastIndex, sql.length());

        return runDML(sqlOut.toString());
    }

    private static String sqlEscape(String input) {
        return "\'" + input.replace("'", "''") + "\'";
    }

    public void shutdown() {
        try {
            try {
                Statement stmt = dbconn.createStatement();
                stmt.execute("SHUTDOWN;");
            } catch (Exception e) {};
            dbconn.close();
            dbconn = null;
            System.gc();
        } catch (Exception e) {
            hostLog.l7dlog( Level.ERROR, LogKeys.host_Backend_ErrorOnShutdown.name(), e);
        }
    }

}
