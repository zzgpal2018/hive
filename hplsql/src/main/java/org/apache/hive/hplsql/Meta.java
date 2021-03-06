/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hive.hplsql;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import org.antlr.v4.runtime.ParserRuleContext;

/**
 * Metadata
 */
public class Meta {
    
  HashMap<String, HashMap<String, Row>> dataTypes = new HashMap<String, HashMap<String, Row>>();
  
  Exec exec;
  boolean trace = false;  
  boolean info = false;
  
  Meta(Exec e) {
    exec = e;  
    trace = exec.getTrace();
    info = exec.getInfo();
  }
  
  /**
   * Get the data type of column (column name is qualified i.e. schema.table.column)
   */
  String getDataType(ParserRuleContext ctx, String conn, String column) {
    String type = null;
    HashMap<String, Row> map = dataTypes.get(conn);
    if (map == null) {
      map = new HashMap<String, Row>();
      dataTypes.put(conn, map);
    }
    ArrayList<String> twoparts = splitIdentifierToTwoParts(column);
    if (twoparts != null) {
      String tab = twoparts.get(0);
      String col = twoparts.get(1).toUpperCase();
      Row row = map.get(tab);
      if (row != null) {
        type = row.getType(col);
      }
      else {
        row = readColumns(ctx, conn, tab, map);
        if (row != null) {
          type = row.getType(col);
        }
      }
    }
    return type;
  }
  
  /**
   * Get data types for all columns of the table
   */
  Row getRowDataType(ParserRuleContext ctx, String conn, String table) {
    HashMap<String, Row> map = dataTypes.get(conn);
    if (map == null) {
      map = new HashMap<String, Row>();
      dataTypes.put(conn, map);
    }
    Row row = map.get(table);
    if (row == null) {
      row = readColumns(ctx, conn, table, map);
    }
    return row;
  }

  List<String> getColumnNames(ParserRuleContext ctx, String conn, String table) {
    Row row = getRowDataType(ctx, conn, table);
    if (row == null) {
      return null;
    }
    return row.getColumns().stream().map(Column::getName).collect(Collectors.toList());
  }

  List<String> getPartitionKeys(ParserRuleContext ctx, String conn, String table) {
    Row row = getRowDataType(ctx, conn, table);
    if (row == null) {
      return null;
    }
    return row.getColumns().stream().filter(Column::isPartitionKey).map(Column::getName).collect(Collectors.toList());
  }
  
  /**
   * Get data types for all columns of the SELECT statement
   */
  Row getRowDataTypeForSelect(ParserRuleContext ctx, String conn, String select) {
    Row row = null;
    Conn.Type connType = exec.getConnectionType(conn); 
    // Hive does not support ResultSetMetaData on PreparedStatement, and Hive DESCRIBE
    // does not support queries, so we have to execute the query with LIMIT 1
    if (connType == Conn.Type.HIVE) {
      String sql = "SELECT * FROM (" + select + ") t LIMIT 1";
      Query query = new Query(sql);
      exec.executeQuery(ctx, query, conn); 
      if (!query.error()) {
        ResultSet rs = query.getResultSet();
        try {
          ResultSetMetaData rm = rs.getMetaData();
          int cols = rm.getColumnCount();
          row = new Row();
          for (int i = 1; i <= cols; i++) {
            String name = rm.getColumnName(i);
            if (name.startsWith("t.")) {
              name = name.substring(2);
            }
            row.addColumn(name, rm.getColumnTypeName(i));
          }
        } 
        catch (Exception e) {
          exec.signal(e);
        }
      }
      else {
        exec.signal(query.getException());
      }
      exec.closeQuery(query, conn);
    }
    else {
      Query query = exec.prepareQuery(ctx, select, conn); 
      if (!query.error()) {
        try {
          PreparedStatement stmt = query.getPreparedStatement();
          ResultSetMetaData rm = stmt.getMetaData();
          int cols = rm.getColumnCount();
          for (int i = 1; i <= cols; i++) {
            String col = rm.getColumnName(i);
            String typ = rm.getColumnTypeName(i);
            if (row == null) {
              row = new Row();
            }
            row.addColumn(col.toUpperCase(), typ);
          }
        }
        catch (Exception e) {
          exec.signal(e);
        }
      }
      exec.closeQuery(query, conn);
    }
    return row;
  }
  
  /**
   * Read the column data from the database and cache it
   */
  Row readColumns(ParserRuleContext ctx, String conn, String table, HashMap<String, Row> map) {
    boolean isPartitionInfo = false;
    Row row = null;
    Conn.Type connType = exec.getConnectionType(conn); 
    if (connType == Conn.Type.HIVE) {
      String sql = "DESCRIBE " + table;
      Query query = new Query(sql);
      exec.executeQuery(ctx, query, conn); 
      if (!query.error()) {
        ResultSet rs = query.getResultSet();
        if (rs == null) {
          return null;
        }
        try {
          while (rs.next()) {
            String col = rs.getString(1);
            String typ = rs.getString(2);
            if (row == null) {
              row = new Row();
            }
            // Hive DESCRIBE outputs "empty_string NULL" row before partition information
            if (col.equals("")) {
              continue;
            }

            if (col.startsWith("#")) {
              if (col.startsWith("# Partition")) {
                isPartitionInfo = true;
              }
              continue;
            }

            if (isPartitionInfo) {
              Column column = row.getColumn(col);
              column.setPartitionKey(true);
            } else {
              row.addColumn(col, typ);
            }
          } 
          map.put(table, row);
        } 
        catch (Exception e) {
          exec.signal(e);
        }
      }
      else {
        exec.signal(query.getException());
      }
      exec.closeQuery(query, conn);
    }
    else {
      Query query = exec.prepareQuery(ctx, "SELECT * FROM " + table, conn); 
      if (!query.error()) {
        try {
          PreparedStatement stmt = query.getPreparedStatement();
          ResultSetMetaData rm = stmt.getMetaData();
          int cols = rm.getColumnCount();
          for (int i = 1; i <= cols; i++) {
            String col = rm.getColumnName(i);
            String typ = rm.getColumnTypeName(i);
            if (row == null) {
              row = new Row();
            }
            row.addColumn(col.toUpperCase(), typ);
          }
          map.put(table, row);
        }
        catch (Exception e) {}
      }
      exec.closeQuery(query, conn);
    }
    return row;
  }
  
  /**
   * Normalize identifier for a database object (convert "" [] to `` i.e.)
   */
  public String normalizeObjectIdentifier(String name) {
    ArrayList<String> parts = splitIdentifier(name);
    if (parts != null) {  // more then one part exist
      StringBuilder norm = new StringBuilder();
      int size = parts.size();
      boolean appended = false;
      for (int i = 0; i < size; i++) {
        if (i == size - 2) {   // schema name
          String schema = getTargetSchemaName(parts.get(i));
          if (schema != null) {
            norm.append(schema);
            appended = true;
          }          
        } else {
          norm.append(normalizeIdentifierPart(parts.get(i)));
          appended = true;
        }
        if (i + 1 < parts.size() && appended) {
          norm.append(".");
        }
      }
      return norm.toString();
    }
    return normalizeIdentifierPart(name);
  }
  
  /**
   * Get the schema name to be used in the final executed SQL
   */
  String getTargetSchemaName(String name) {
    if (name.equalsIgnoreCase("dbo") || name.equalsIgnoreCase("[dbo]")) {
      return null;
    }
    return normalizeIdentifierPart(name);
  }  
  
  /**
   * Normalize identifier (single part) - convert "" [] to `` i.e.
   */
  public String normalizeIdentifierPart(String name) {
    char start = name.charAt(0);
    char end = name.charAt(name.length() - 1);
    if ((start == '[' && end == ']') || (start == '"' && end == '"')) {
      return '`' + name.substring(1, name.length() - 1) + '`'; 
    }
    return name;
  }
  
  /**
   * Split qualified object to 2 parts: schema.tab.col -> schema.tab|col; tab.col -> tab|col 
   */
  public ArrayList<String> splitIdentifierToTwoParts(String name) {
    ArrayList<String> parts = splitIdentifier(name);    
    ArrayList<String> twoparts = null;
    if (parts != null) {
      StringBuilder id = new StringBuilder();
      int i = 0;
      for (; i < parts.size() - 1; i++) {
        id.append(parts.get(i));
        if (i + 1 < parts.size() - 1) {
          id.append(".");
        }
      }
      twoparts = new ArrayList<String>();
      twoparts.add(id.toString());
      id.setLength(0);
      id.append(parts.get(i));
      twoparts.add(id.toString());
    }
    return twoparts;
  }
  
  /**
   * Split identifier to parts (schema, table, colum name etc.)
   * @return null if identifier contains single part
   */
  public ArrayList<String> splitIdentifier(String name) {
    ArrayList<String> parts = null;
    int start = 0;
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      char del = '\0';
      if (c == '`' || c == '"') {
        del = c;        
      }
      else if (c == '[') {
        del = ']';
      }
      if (del != '\0') {
        for (int j = i + 1; i < name.length(); j++) {
          i++;
          if (name.charAt(j) == del) {
            break;
          }
        }
        continue;
      }
      if (c == '.') {
        if (parts == null) {
          parts = new ArrayList<String>();
        }
        parts.add(name.substring(start, i));
        start = i + 1;
      }
    }
    if (parts != null) {
      parts.add(name.substring(start));
    }
    return parts;
  }
}



