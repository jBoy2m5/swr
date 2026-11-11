import java.sql.*;
import java.util.*;

final class Db {
    interface Transaction<T> { T run(Connection c) throws SQLException; }

    private static String setting(String name, String fallback) {
        return System.getenv().getOrDefault(name, fallback);
    }
    static Connection open() throws SQLException {
        return DriverManager.getConnection(
            setting("BMM_JDBC","jdbc:sqlserver://localhost:1433;databaseName=BepMeMoiMiniDB;encrypt=true;trustServerCertificate=true;loginTimeout=4"),
            setting("BMM_DB_USER","sa"),setting("BMM_DB_PASSWORD",""));
    }
    static <T> T tx(Transaction<T> fn) throws SQLException {
        try (Connection c=open()) {
            c.setAutoCommit(false);
            try { T value=fn.run(c); c.commit(); return value; }
            catch(SQLException|RuntimeException e) {
                try { c.rollback(); } catch(SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        }
    }
    static PreparedStatement prepare(Connection c,String sql,Object... args) throws SQLException {
        PreparedStatement s=c.prepareStatement(sql);
        try {
            for(int i=0;i<args.length;i++) {
                Object value=args[i];
                if(value instanceof byte[] b) s.setBytes(i+1,b);
                else s.setObject(i+1,value);
            }
            return s;
        } catch(SQLException ex) {s.close();throw ex;}
    }
    static List<Map<String,Object>> rows(Connection c,String sql,Object... args) throws SQLException {
        try(var s=prepare(c,sql,args);var r=s.executeQuery()) {
            var list=new ArrayList<Map<String,Object>>();
            var md=r.getMetaData();
            while(r.next()) {
                var map=new LinkedHashMap<String,Object>();
                for(int i=1;i<=md.getColumnCount();i++) map.put(md.getColumnLabel(i),r.getObject(i));
                list.add(map);
            }
            return list;
        }
    }
    static List<Map<String,Object>> rows(String sql,Object... args) throws SQLException {
        try(var c=open()) {return rows(c,sql,args);}
    }
    static Map<String,Object> one(Connection c,String sql,Object... args) throws SQLException {
        var list=rows(c,sql,args); return list.isEmpty()?Map.of():list.get(0);
    }
    static Map<String,Object> one(String sql,Object... args) throws SQLException {
        try(var c=open()) {return one(c,sql,args);}
    }
    static int update(Connection c,String sql,Object... args) throws SQLException {
        try(var s=prepare(c,sql,args)){return s.executeUpdate();}
    }
    static int update(String sql,Object... args) throws SQLException {
        try(var c=open()){return update(c,sql,args);}
    }
    static int number(Map<String,Object> row,String key) {return ((Number)row.get(key)).intValue();}
    static long longNumber(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    static String str(Map<String,Object> row,String key) {return Objects.toString(row.get(key),"");}
    static boolean yes(Map<String,Object> row,String key) {return Boolean.TRUE.equals(row.get(key));}
}
