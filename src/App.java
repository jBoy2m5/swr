import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class App {
    static final Map<String,Session> SESSIONS=new ConcurrentHashMap<>();
    static final long SESSION_MS=30*60*1000;
    static final SecureRandom RNG=new SecureRandom();
    static final String OFFERS="""
        SELECT p.*,p.category kind,COALESCE((SELECT MAX(s.available) FROM dbo.Stock s WHERE s.product=p.id),0) available
        FROM dbo.Products p
        """;
    static final class Session {
        final String key=UUID.randomUUID().toString(), token=UUID.randomUUID().toString();
        final Map<Integer,Integer> bag=new LinkedHashMap<>();
        volatile long used=System.currentTimeMillis();
        int customer;
    }
    record Request(HttpExchange h,Session session,Map<String,String> data,Map<String,Object> customer) {
        String get(String k){return data.getOrDefault(k,"").strip();}
        String field(String k,int max){String value=get(k);if(value.length()>max)throw new IllegalArgumentException("Thông tin quá dài.");return value;}
        String need(String k,int max){String value=field(k,max);if(value.isBlank())throw new IllegalArgumentException("Vui lòng điền đủ thông tin.");return value;}
        int id(String key){int id=Integer.parseInt(get(key));if(id<=0)throw new IllegalArgumentException("Mã không hợp lệ.");return id;}
    }
    record Quote(Map<String,Object> shop,List<Map<String,Object>> rows,long subtotal,int fee) {
        long total(){return subtotal+fee;}
    }
    public static void main(String[] args) throws Exception {
        int count=Db.number(Db.one("SELECT COUNT(*) n FROM dbo.Products"),"n");
        int port=Integer.parseInt(System.getenv().getOrDefault("BMM_PORT","8091"));
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
        server.createContext("/",App::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        var timer=Executors.newSingleThreadScheduledExecutor();
        timer.scheduleWithFixedDelay(()->SESSIONS.entrySet().removeIf(e->System.currentTimeMillis()-e.getValue().used>SESSION_MS),1,1,TimeUnit.MINUTES);
        server.start();
        System.out.printf("SQL Server: %d sản phẩm%nBếp Mẻ Mới: http://localhost:%d/%n",count,port);
    }
    static void handle(HttpExchange h) throws IOException {
        String path=h.getRequestURI().getPath();
        h.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        h.getResponseHeaders().set("X-Frame-Options","DENY");
        h.getResponseHeaders().set("Cache-Control","no-store");
        try{
            boolean post=h.getRequestMethod().equals("POST");
            if(!post&&!h.getRequestMethod().equals("GET")){send(h,405,Ui.error("Phương thức không hỗ trợ."));return;}
            if(!post&&Set.of("/style.css","/baking.svg").contains(path)){
                bytes(h,200,path.endsWith(".svg")?"image/svg+xml":"text/css",Files.readAllBytes(Path.of("public",path.substring(1))));return;
            }
            Session s=session(h);
            synchronized(s){
                byte[] form=post?h.getRequestBody().readNBytes(25_001):new byte[0];
                if(form.length>25_000){send(h,413,Ui.error("Dữ liệu quá lớn."));return;}
                var data=decode(post?new String(form,StandardCharsets.UTF_8):h.getRequestURI().getRawQuery());
                if(post&&!s.token.equals(data.get("csrf"))){send(h,403,Ui.error("Phiên hết hạn. Hãy tải lại trang."));return;}
                var person=s.customer==0?Map.<String,Object>of():Db.one("SELECT id,name,email FROM dbo.Customers WHERE id=?",s.customer);
                if(person.isEmpty())s.customer=0;
                var r=new Request(h,s,data,person);
                if(post)post(path,r);else get(path,r);
            }
        }catch(IllegalArgumentException e){send(h,400,Ui.error(e instanceof NumberFormatException?"Thông tin số không hợp lệ.":Objects.toString(e.getMessage(),"Thông tin chưa hợp lệ.")));}
        catch(SQLException e){
            if(e.getErrorCode()==2601||e.getErrorCode()==2627)send(h,409,Ui.error("Email đã có tài khoản."));
            else{e.printStackTrace();send(h,503,Ui.error("Không đọc hoặc lưu được dữ liệu SQL Server."));}
        }catch(Exception e){e.printStackTrace();send(h,500,Ui.error("Có lỗi khi mở trang."));}
        finally{h.close();}
    }
    static Session session(HttpExchange h){
        String cookies=Objects.toString(h.getRequestHeaders().getFirst("Cookie"),"");
        for(String pair:cookies.split(";")){
            String value=pair.strip();
            if(value.startsWith("bmmmini=")){
                var s=SESSIONS.get(value.substring(8));
                if(s!=null&&System.currentTimeMillis()-s.used<=SESSION_MS){s.used=System.currentTimeMillis();return s;}
            }
        }
        return fresh(h,0);
    }
    static Session fresh(HttpExchange h,int customer){
        Session s=new Session();s.customer=customer;SESSIONS.put(s.key,s);
        h.getResponseHeaders().set("Set-Cookie","bmmmini="+s.key+"; Path=/; HttpOnly; SameSite=Lax");return s;
    }
    static Map<String,String> decode(String encoded){
        var map=new HashMap<String,String>();
        if(encoded!=null&&!encoded.isBlank())for(String pair:encoded.split("&")){
            var split=pair.split("=",2);
            String key=URLDecoder.decode(split[0],StandardCharsets.UTF_8);
            String value=split.length==2?URLDecoder.decode(split[1],StandardCharsets.UTF_8):"";
            if(map.putIfAbsent(key,value)!=null)throw new IllegalArgumentException("Dữ liệu trùng.");
        }
        return map;
    }
    static void bytes(HttpExchange h,int code,String type,byte[] value)throws IOException{
        h.getResponseHeaders().set("Content-Type",type+"; charset=UTF-8");
        h.sendResponseHeaders(code,value.length);h.getResponseBody().write(value);
    }
    static void send(HttpExchange h,int code,String html)throws IOException{bytes(h,code,"text/html",html.getBytes(StandardCharsets.UTF_8));}
    static void redirect(Request r,String target)throws IOException{r.h().getResponseHeaders().set("Location",target);r.h().sendResponseHeaders(303,-1);}
    static boolean signed(Request r,String next)throws IOException{
        if(r.session().customer!=0)return true;
        redirect(r,"/login"+(next.equals("checkout")?"?next=checkout":""));return false;
    }
    static List<Map<String,Object>> products(String kind,String q)throws SQLException{
        return Db.rows(OFFERS+"""
            WHERE (?='' OR p.category=?) AND (?='' OR CHARINDEX(?,CONCAT(p.title,N' ',p.pack))>0) ORDER BY p.id
            """,kind,kind,q,q);
    }
    static List<Map<String,Object>> basket(Request r)throws SQLException{
        if(r.session().customer!=0)return Db.rows("""
            SELECT p.*,c.qty FROM dbo.Carts c JOIN dbo.Products p ON p.id=c.product WHERE c.customer=? ORDER BY p.id
            """,r.session().customer);
        var out=new ArrayList<Map<String,Object>>();
        for(var line:r.session().bag.entrySet()){
            var product=Db.one("SELECT * FROM dbo.Products WHERE id=?",line.getKey());
            if(!product.isEmpty()){product.put("qty",line.getValue());out.add(product);}
        }
        return out;
    }
    static void get(String path,Request r)throws IOException,SQLException{
        String body;
        switch(path){
            case "/" -> body=Ui.home(products("",""));
            case "/pantry" -> {String kind=r.field("kind",15),q=r.field("q",100);body=Ui.catalogue(r,products(kind,q),kind,q);}
            case "/item" -> {
                var item=Db.one(OFFERS+" WHERE p.id=?",r.id("id"));
                if(item.isEmpty()){send(r.h(),404,Ui.error("Không tìm thấy sản phẩm."));return;}
                body=Ui.item(r,item);
            }
            case "/bag" -> body=Ui.bag(r,basket(r));
            case "/login","/register" -> body=Ui.auth(r,path);
            case "/checkout" -> {
                if(!signed(r,"checkout"))return;
                var shops=Db.rows("SELECT * FROM dbo.Shops ORDER BY id");
                Quote q=null;String error="";
                if(!r.get("shop").isEmpty()){
                    try(var c=Db.open()){q=quote(c,r.session().customer,r.id("shop"),r.get("mode"),r.get("zone"),false);}
                    catch(IllegalArgumentException ex){error=ex.getMessage();}
                }
                body=Ui.checkout(r,shops,q,error);
            }
            case "/order" -> {
                if(!signed(r,""))return;
                int id=r.id("id");
                var order=Db.one("SELECT o.*,s.name shopName FROM dbo.Orders o JOIN dbo.Shops s ON s.id=o.shop WHERE o.id=? AND o.customer=?",id,r.session().customer);
                if(order.isEmpty()){send(r.h(),404,Ui.error("Không tìm thấy đơn hàng."));return;}
                body=Ui.order(order,Db.rows("SELECT * FROM dbo.OrderItems WHERE orderId=? ORDER BY product",id));
            }
            case "/orders" -> {
                if(!signed(r,""))return;
                body=Ui.orders(Db.rows("SELECT * FROM dbo.Orders WHERE customer=? ORDER BY id DESC",r.session().customer));
            }
            default -> {send(r.h(),404,Ui.error("Không tìm thấy trang."));return;}
        }
        send(r.h(),200,Ui.page(r,body));
    }
    static void post(String path,Request r)throws IOException,SQLException{
        switch(path){
            case "/register" -> {
                String name=r.need("name",80),email=email(r.get("email")),pass=r.get("password");
                if(pass.length()<10||pass.length()>100)throw new IllegalArgumentException("Mật khẩu cần từ 10 đến 100 ký tự.");
                byte[] salt=new byte[16];RNG.nextBytes(salt);
                Db.update("INSERT dbo.Customers(name,email,passwordHash) VALUES(?,?,?)",name,email,HexFormat.of().formatHex(salt)+":"+HexFormat.of().formatHex(derive(pass,salt)));
                redirect(r,"/login?created=1"+(r.get("next").equals("checkout")?"&next=checkout":""));
            }
            case "/login" -> {
                var row=Db.one("SELECT id,passwordHash FROM dbo.Customers WHERE email=?",email(r.get("email")));
                String hash=Db.str(row,"passwordHash"),pass=r.get("password");
                if(hash.isEmpty()||pass.length()>100)throw new IllegalArgumentException("Email hoặc mật khẩu chưa đúng.");
                var hex=HexFormat.of();
                if(!MessageDigest.isEqual(hex.parseHex(hash.substring(33)),derive(pass,hex.parseHex(hash.substring(0,32)))))
                    throw new IllegalArgumentException("Email hoặc mật khẩu chưa đúng.");
                int id=Db.number(row,"id");
                if(!r.session().bag.isEmpty())Db.tx(c->{
                    lock(c,id);
                    for(var item:r.session().bag.entrySet()){
                        int available=available(c,item.getKey());
                        int qty=Math.min(Math.min(99,available),item.getValue()+quantity(c,id,item.getKey()));
                        if(qty>0)setCart(c,id,item.getKey(),qty);
                    }
                    return null;
                });
                SESSIONS.remove(r.session().key);fresh(r.h(),id);
                redirect(r,r.get("next").equals("checkout")?"/checkout":"/pantry");
            }
            case "/logout" -> {SESSIONS.remove(r.session().key);fresh(r.h(),0);redirect(r,"/");}
            case "/bag" -> {
                int id=r.id("id"),qty=Integer.parseInt(r.get("qty"));boolean add=r.get("action").equals("add");
                if(qty<0||qty>99)throw new IllegalArgumentException("Số lượng không hợp lệ.");
                int user=r.session().customer;
                if(user==0){
                    int next=add?qty+r.session().bag.getOrDefault(id,0):qty;
                    try(var c=Db.open()){validateStock(c,id,next);}
                    if(next==0)r.session().bag.remove(id);else r.session().bag.put(id,next);
                }else Db.tx(c->{lock(c,user);int next=add?qty+quantity(c,user,id):qty;validateStock(c,id,next);setCart(c,user,id,next);return null;});
                redirect(r,"/bag");
            }
            case "/checkout" -> {
                if(!signed(r,"checkout"))return;
                int user=r.session().customer,shop=r.id("shop");
                String mode=r.get("mode"),zone=r.field("zone",80),phone=r.get("phone"),recipient=r.need("recipient",80);
                String addr=mode.equals("DELIVERY")?r.need("address",240):"";
                if(!phone.matches("[0-9]{9,11}"))throw new IllegalArgumentException("Điện thoại cần 9–11 chữ số.");
                String token=UUID.fromString(r.get("token")).toString();
                long shown=Long.parseLong(r.get("total"));
                int order=Db.tx(c->{
                    lock(c,user);
                    var existing=Db.one(c,"SELECT id FROM dbo.Orders WHERE customer=? AND checkoutToken=?",user,token);
                    if(!existing.isEmpty())return Db.number(existing,"id");
                    Quote q=quote(c,user,shop,mode,zone,true);
                    if(shown!=q.total())throw new IllegalArgumentException("Tổng tiền đã thay đổi. Vui lòng kiểm tra lại giỏ hàng.");
                    int id=Db.number(Db.one(c,"""
                       INSERT dbo.Orders(customer,checkoutToken,shop,mode,recipient,phone,address,fee,total)
                       OUTPUT inserted.id VALUES(?,?,?,?,?,?,?,?,?)
                       """,user,token,shop,mode,recipient,phone,mode.equals("PICKUP")?Db.str(q.shop(),"address"):addr,q.fee(),q.total()),"id");
                    for(var l:q.rows()){
                        int product=Db.number(l,"id"),n=Db.number(l,"qty");
                        if(Db.update(c,"UPDATE dbo.Stock SET reserved=reserved+? WHERE shop=? AND product=? AND available>=?",n,shop,product,n)!=1)
                            throw new IllegalArgumentException("Tồn kho vừa thay đổi. Vui lòng kiểm tra lại.");
                        Db.update(c,"INSERT dbo.OrderItems(orderId,product,title,qty,unitPrice) VALUES(?,?,?,?,?)",id,product,Db.str(l,"title")+" · "+Db.str(l,"pack"),n,Db.number(l,"price"));
                    }
                    Db.update(c,"DELETE dbo.Carts WHERE customer=?",user);return id;
                });
                redirect(r,"/order?id="+order);
            }
            default -> send(r.h(),405,Ui.error("Thao tác không hỗ trợ."));
        }
    }
    static String email(String raw){
        String value=raw.toLowerCase(Locale.ROOT);
        if(value.length()>254||!value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw new IllegalArgumentException("Email không hợp lệ.");
        return value;
    }
    static byte[] derive(String password,byte[] salt){
        var key=new PBEKeySpec(password.toCharArray(),salt,210_000,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(key).getEncoded();}
        catch(GeneralSecurityException e){throw new IllegalStateException(e);}
        finally{key.clearPassword();}
    }
    static void lock(Connection c,int user)throws SQLException{
        if(Db.one(c,"SELECT id FROM dbo.Customers WITH (UPDLOCK,HOLDLOCK) WHERE id=?",user).isEmpty())
            throw new IllegalArgumentException("Tài khoản không tồn tại.");
    }
    static int available(Connection c,int id)throws SQLException{
        return Db.number(Db.one(c,"SELECT COALESCE(MAX(available),0) n FROM dbo.Stock WHERE product=?",id),"n");
    }
    static int quantity(Connection c,int user,int id)throws SQLException{
        return Db.number(Db.one(c,"SELECT COALESCE(MAX(qty),0) n FROM dbo.Carts WHERE customer=? AND product=?",user,id),"n");
    }
    static void validateStock(Connection c,int id,int qty)throws SQLException{
        if(qty>0&&(qty>99||qty>available(c,id)))throw new IllegalArgumentException("Số lượng vượt quá tồn khả dụng.");
    }
    static void setCart(Connection c,int user,int id,int qty)throws SQLException{
        if(qty==0)Db.update(c,"DELETE dbo.Carts WHERE customer=? AND product=?",user,id);
        else if(Db.update(c,"UPDATE dbo.Carts SET qty=? WHERE customer=? AND product=?",qty,user,id)==0)
            Db.update(c,"INSERT dbo.Carts(customer,product,qty) VALUES(?,?,?)",user,id,qty);
    }
    static Quote quote(Connection c,int user,int shop,String mode,String zone,boolean locking)throws SQLException{
        var location=Db.one(c,"SELECT * FROM dbo.Shops WHERE id=?",shop);
        if(location.isEmpty()||!Set.of("PICKUP","DELIVERY").contains(mode)||
                (mode.equals("DELIVERY")&&!zone.equals(Db.str(location,"zone"))))
            throw new IllegalArgumentException("Cửa hàng chưa hỗ trợ khu vực giao đã chọn.");
        var rows=Db.rows(c,"""
            SELECT p.*,b.qty,s.available FROM dbo.Carts b JOIN dbo.Products p ON p.id=b.product
            LEFT JOIN dbo.Stock s %s ON s.shop=? AND s.product=p.id
            WHERE b.customer=? ORDER BY p.id
            """.formatted(locking?"WITH (UPDLOCK,HOLDLOCK)":""),shop,user);
        if(rows.isEmpty())throw new IllegalArgumentException("Giỏ hàng đang trống.");
        long subtotal=0;
        for(var row:rows){
            if(row.get("available")==null||Db.number(row,"available")<Db.number(row,"qty"))
                throw new IllegalArgumentException("Cửa hàng không đủ hàng cho "+Db.str(row,"title")+".");
            subtotal+=Db.longNumber(row,"price")*Db.number(row,"qty");
        }
        return new Quote(location,rows,subtotal,mode.equals("PICKUP")?0:Db.number(location,"fee"));
    }
}
