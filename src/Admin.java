import java.io.IOException;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;

final class Admin {
    static final String EMAIL="admin@bepmemoi.local";
    private static final Set<String> FILTERS=Set.of("NEW","CONFIRMED","PACKED","READY","SHIPPING","COMPLETED","CANCELLED");

    static boolean allowed(App.Request r) throws IOException {
        if(r.admin())return true;
        if(r.customer().isEmpty())App.redirect(r,"/admin/login");
        else App.send(r.h(),403,Ui.error("Bạn cần tài khoản quản trị để mở trang này."));
        return false;
    }
    static String login(App.Request r) {
        return "<section class='panel narrow'>"+Ui.heading("Đăng nhập quản trị","")+
               Ui.form(r,"/admin/login")+Ui.input("Tên đăng nhập","username","","text",40,true)+
               Ui.input("Mật khẩu","password","","password",100,true)+Ui.button("Đăng nhập")+"</form></section>";
    }
    static void signIn(App.Request r) throws SQLException,IOException {
        String pass=r.get("password");
        if(!r.get("username").equals("admin")||pass.length()>100)throw new IllegalArgumentException("Tên đăng nhập hoặc mật khẩu chưa đúng.");
        var row=Db.one("SELECT id,passwordHash FROM dbo.Customers WHERE email=? AND isAdmin=1",EMAIL);
        String hash=Db.str(row,"passwordHash");
        var hex=HexFormat.of();
        if(hash.isEmpty()||!MessageDigest.isEqual(hex.parseHex(hash.substring(33)),App.derive(pass,hex.parseHex(hash.substring(0,32)))))
            throw new IllegalArgumentException("Tên đăng nhập hoặc mật khẩu chưa đúng.");
        App.SESSIONS.remove(r.session().key);
        App.fresh(r.h(),Db.number(row,"id"));
        App.redirect(r,"/admin");
    }
    static List<String> next(String from,String mode) {
        return switch(from){
            case "NEW" -> List.of("CONFIRMED","CANCELLED");
            case "CONFIRMED" -> List.of("PACKED","CANCELLED");
            case "PACKED" -> List.of(mode.equals("PICKUP")?"READY":"SHIPPING");
            case "READY","SHIPPING" -> List.of("COMPLETED");
            default -> List.of();
        };
    }
    static String action(String to){
        return switch(to){
            case "CONFIRMED"->"Xác nhận"; case "PACKED"->"Đóng gói";case "SHIPPING"->"Bàn giao vận chuyển";
            case "READY"->"Sẵn sàng nhận";case "COMPLETED"->"Hoàn tất";case "CANCELLED"->"Hủy đơn";default->to;
        };
    }
    static String page(App.Request r) throws SQLException {
        String filter=FILTERS.contains(r.get("status"))?r.get("status"):"";
        var orders=Db.rows("""
            SELECT TOP(100) o.*,s.name shopName,
                (SELECT STRING_AGG(CONCAT(i.title,N' × ',i.qty),N', ') FROM dbo.OrderItems i WHERE i.orderId=o.id) items
            FROM dbo.Orders o JOIN dbo.Shops s ON s.id=o.shop
            WHERE (?='' OR o.status=?) ORDER BY o.id DESC
            """,filter,filter);
        StringBuilder b=new StringBuilder(Ui.heading("Quản trị đơn hàng","Xác nhận, đóng gói và bàn giao đơn."));
        if(r.get("saved").equals("1"))b.append("<p class=notice role=status>Đã cập nhật đơn hàng.</p>");
        b.append("<form class=compact action='/admin'><label>Trạng thái<select name=status>").append(Ui.opt("","Tất cả",filter));
        for(String state:List.of("NEW","CONFIRMED","PACKED","READY","SHIPPING","COMPLETED","CANCELLED"))
            b.append(Ui.opt(state,Ui.status(state),filter));
        b.append("</select></label>").append(Ui.button("Lọc")).append("</form><div class=admin-orders>");
        if(orders.isEmpty())return b.append(Ui.blank("Chưa có đơn phù hợp.")).append("</div>").toString();
        for(var order:orders){
            String state=Db.str(order,"status"),mode=Db.str(order,"mode");
            b.append("<article class=panel><div class=section-head><h2>Đơn #").append(Ui.v(order,"id"))
             .append("</h2><strong>").append(Ui.e(Ui.status(state))).append("</strong></div><p>")
             .append(Ui.v(order,"shopName")).append(" · ").append(mode.equals("PICKUP")?"Nhận tại cửa hàng":"Giao hàng")
             .append("</p><p>").append(Ui.v(order,"items")).append("</p><p>").append(Ui.v(order,"recipient"))
             .append(" · ").append(Ui.v(order,"phone")).append(" · ").append(Ui.v(order,"address"))
             .append("</p><p><strong>").append(Ui.money(order,"total")).append("</strong> · COD</p>");
            var steps=next(state,mode);
            if(!steps.isEmpty()){
                b.append(Ui.form(r,"/admin")).append(Ui.hidden("id",order.get("id"))).append(Ui.hidden("filter",filter))
                 .append(Ui.input("Ghi chú bàn giao hoặc lý do hủy","note","","text",200,false)).append("<div class=admin-actions>");
                for(String step:steps)b.append("<button name=next value='").append(step).append("'>").append(Ui.e(action(step))).append("</button>");
                b.append("</div></form>");
            }
            b.append("</article>");
        }
        return b.append("</div>").toString();
    }
    static void change(App.Request r) throws IOException,SQLException {
        int id=r.id("id");
        String target=r.get("next"),note=r.field("note",200);
        if(Set.of("CANCELLED","SHIPPING","COMPLETED").contains(target)&&note.isBlank())
            throw new IllegalArgumentException("Nhập lý do hủy hoặc thông tin bàn giao.");
        Db.tx(c->{
            var order=Db.one(c,"SELECT status,shop,mode FROM dbo.Orders WITH (UPDLOCK,HOLDLOCK) WHERE id=?",id);
            String current=Db.str(order,"status");
            if(order.isEmpty()||!next(current,Db.str(order,"mode")).contains(target))
                throw new IllegalArgumentException("Trạng thái đơn đã thay đổi hoặc thao tác không hợp lệ.");
            if(target.equals("PACKED")||target.equals("CANCELLED")){
                int count=Db.number(Db.one(c,"SELECT COUNT(*) n FROM dbo.OrderItems WHERE orderId=?",id),"n");
                int changed=Db.update(c,"""
                    UPDATE s SET reserved=s.reserved-i.qty,
                      onHand=s.onHand-CASE WHEN ?=1 THEN i.qty ELSE 0 END
                    FROM dbo.Stock s JOIN dbo.OrderItems i ON i.product=s.product
                    WHERE s.shop=? AND i.orderId=? AND s.reserved>=i.qty
                    """,target.equals("PACKED")?1:0,Db.number(order,"shop"),id);
                if(count==0||changed!=count)throw new IllegalArgumentException("Số hàng đã giữ không khớp với đơn.");
            }
            Db.update(c,"UPDATE dbo.Orders SET status=? WHERE id=?",target,id);
            Db.update(c,"INSERT dbo.OrderEvents(orderId,actor,oldStatus,newStatus,note) VALUES(?,?,?,?,?)",
                id,r.session().customer,current,target,note.isEmpty()?null:note);
            return null;
        });
        String filter=FILTERS.contains(r.get("filter"))?r.get("filter"):"";
        App.redirect(r,"/admin?saved=1&status="+filter);
    }
}
