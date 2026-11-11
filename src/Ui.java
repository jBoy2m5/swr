import java.text.NumberFormat;
import java.util.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class Ui {
    static String e(Object text){return Objects.toString(text,"").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    static String v(Map<String,Object> row,String key){return e(row.get(key));}
    static String money(long n){return NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN")).format(n)+" ₫";}
    static String money(Map<String,Object> row,String key){return money(Db.longNumber(row,key));}
    static String enc(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    static String hidden(String key,Object value){return "<input type=hidden name='"+e(key)+"' value='"+e(value)+"'>";}
    static String form(App.Request r,String action){return "<form method=post action='"+action+"'>"+hidden("csrf",r.session().token);}
    static String input(String label,String key,String value,String type,int max,boolean required){
        return "<label>"+e(label)+"<input name='"+key+"' type='"+type+"' maxlength='"+max+"' value='"+e(value)+"'"+(required?" required":"")+"></label>";
    }
    static String button(String title){return "<button>"+e(title)+"</button>";}
    static String opt(Object value,String label,String current){return "<option value='"+e(value)+"'"+(Objects.toString(value).equals(current)?" selected":"")+">"+e(label)+"</option>";}
    static String heading(String title,String sub){return "<div class=page-title><h1>"+e(title)+"</h1>"+(sub.isEmpty()?"":"<p>"+e(sub)+"</p>")+"</div>";}
    static String blank(String label){return "<div class=blank>"+e(label)+"</div>";}
    static String page(App.Request r,String body) {
        boolean signed=r!=null&&r.session().customer!=0;
        String account=signed?(r.admin()?"<a href='/admin'>Quản trị</a>":"<a href='/orders'>Đơn của tôi</a>")+form(r,"/logout")+"<button class=link>Đăng xuất</button></form>":"<a href='/login'>Đăng nhập</a>";
        return """
            <!doctype html><html lang=vi><head><meta charset=UTF-8><meta name=viewport content='width=device-width,initial-scale=1'>
            <title>Bếp Mẻ Mới · Gia Hòa Phát</title><link rel=stylesheet href=/style.css></head>
            <body><a class=skip href='#main'>Đến nội dung</a><header><div class=bar>
            <a class=brand href='/'><span class=brand-icon aria-hidden=true>✦</span><span>Bếp Mẻ Mới<small>Gia Hòa Phát</small></span></a>
            <nav aria-label='Điều hướng chính'><a href='/pantry'>Cửa hàng</a><a href='/bag'>Giỏ hàng</a>
            """+account+"</nav></div></header><main id=main>"+body+
            "</main><footer><b>Bếp Mẻ Mới</b><a href='/admin/login'>Quản trị</a></footer></body></html>";
    }
    static String error(String reason){return page(null,heading("Chưa thể tiếp tục",reason)+"<a class=button href='/pantry'>Về cửa hàng</a>");}
    static String kind(String name){return switch(name){case "ingredients"->"Nguyên liệu";case "tools"->"Dụng cụ";case "equipment"->"Thiết bị";default->"Tất cả";};}
    static String status(Object input){
        return switch(Objects.toString(input,"")){
            case "PAYMENT_PENDING"->"Chờ thanh toán"; case "NEW"->"Chờ xác nhận";case "CONFIRMED"->"Đã xác nhận";case "PACKED"->"Đã đóng gói";
            case "SHIPPING"->"Đang giao";case "READY"->"Sẵn sàng nhận";case "COMPLETED"->"Hoàn tất";case "CANCELLED"->"Đã hủy"; default->Objects.toString(input,"");};
    }
    static String home(List<Map<String,Object>> items) {
        return """
          <section class=hero>
            <div><h1>Mẻ bánh tiếp theo<br>bắt đầu ở đây<span>.</span></h1>
            <p>Tìm nguyên liệu theo quy cách, chọn dụng cụ vừa tay và nhận hàng theo cách thuận tiện cho bạn.</p>
            <a class=button href='/pantry'>Khám phá cửa hàng <span aria-hidden=true>→</span></a></div>
            <img src='/baking.svg' alt='Minh họa bát trộn, phới và nguyên liệu trên bàn bếp' width=640 height=450>
          </section>
          <section class=shelves aria-label='Danh mục'>
          <a href='/pantry?kind=ingredients'><span>NGUYÊN LIỆU</span><strong>Bột, men &amp; sô cô la</strong><i>↗</i></a>
          <a href='/pantry?kind=tools'><span>DỤNG CỤ</span><strong>Khuôn &amp; phới trộn</strong><i>↗</i></a>
          <a href='/pantry?kind=equipment'><span>THIẾT BỊ</span><strong>Cân &amp; máy đánh trứng</strong><i>↗</i></a>
          </section>
          <div class=section-head><h2>Đang có trên kệ</h2><a href='/pantry'>Xem toàn bộ →</a></div>
          """+cards(items.stream().filter(x->Db.number(x,"available")>0).limit(4).toList());
    }
    static String catalogue(App.Request r,List<Map<String,Object>> items,String kind,String q){
        StringBuilder tabs=new StringBuilder("<div class=tabs>");
        for(String k:List.of("","ingredients","tools","equipment"))
            tabs.append("<a class='").append(kind.equals(k)?"current":"").append("' href='/pantry?kind=").append(k).append("&amp;q=").append(e(enc(q))).append("'>").append(kind(k)).append("</a>");
        return heading("Cửa hàng","Tìm theo tên, mã sản phẩm hoặc quy cách.")+
            "<form class=search action='/pantry'>"+hidden("kind",kind)+input("Tìm sản phẩm","q",q,"search",100,false)+button("Tìm kiếm")+"</form>"+
            tabs+"</div>"+cards(items);
    }
    static String cards(List<Map<String,Object>> items) {
        if(items.isEmpty())return blank("Chưa tìm thấy sản phẩm phù hợp.");
        StringBuilder b=new StringBuilder("<div class=cards>");
        for(var x:items){
            String kind=Db.str(x,"kind");boolean in=Db.number(x,"available")>0;
            b.append("<article class=product><a href='/item?id=").append(v(x,"id")).append("'>").append(art(kind))
             .append("<div class=product-body><span class=category>").append(kind(kind)).append(" / ").append(v(x,"pack"))
             .append("</span><h3>").append(v(x,"title")).append("</h3><div class=product-foot><b>")
             .append(money(x,"price")).append("</b><span>").append(in?"Còn hàng":"Hết hàng").append("</span></div></div></a></article>");
        }
        return b+"</div>";
    }
    static String art(String kind) {
        String line=switch(kind){
            case "tools"->"<ellipse cx='88' cy='66' rx='40' ry='30'/><ellipse cx='88' cy='66' rx='30' ry='21'/><path d='M126 72l24 14-6 11-23-13'/>";
            case "equipment"->"<rect x='43' y='44' width='91' height='57' rx='9'/><rect x='52' y='53' width='55' height='39' rx='3'/><path d='M116 62h8m-8 15h8M69 33h36'/>";
            default->"<path d='M64 35h48l-9 14 19 22v29H55V71l17-22zm4 44h39m-37-13h34'/><path d='M132 38v21m-10-10h20'/>";
        };
        return "<div class='art "+e(kind)+"' aria-hidden=true><svg viewBox='0 0 180 130' fill='none' stroke='currentColor' stroke-width='4' stroke-linecap='round' stroke-linejoin='round'>"+line+"</svg></div>";
    }
    static String item(App.Request r,Map<String,Object> x){
        StringBuilder b=new StringBuilder("<div class=detail>"+art(Db.str(x,"kind"))+"<section class=panel><a href='/pantry'>← Cửa hàng</a>"+
            heading(Db.str(x,"title"),kind(Db.str(x,"kind"))+" · "+Db.str(x,"pack"))+"<p>"+v(x,"description")+"</p><div class=price>"+money(x,"price")+"</div>");
        if(Db.number(x,"available")>0)b.append(form(r,"/bag")).append(hidden("action","add")).append(hidden("id",x.get("id")))
            .append("<label>Số lượng<input type=number name=qty value=1 min=1 max=99 required></label>").append(button("Thêm vào giỏ")).append("</form>");
        else b.append("<p class=notice>Sản phẩm đang hết hàng.</p>");
        return b+"</section></div>";
    }
    static String bag(App.Request r,List<Map<String,Object>> items){
        StringBuilder b=new StringBuilder(heading("Giỏ hàng","Kiểm tra quy cách và số lượng trước khi đặt."));
        if(items.isEmpty()) return b+blank("Giỏ hàng đang trống.")+"<a class=button href='/pantry'>Chọn sản phẩm</a>";
        long total=0;
        b.append("<div class=panel>");
        for(var x:items){
            total+=Db.longNumber(x,"price")*Db.number(x,"qty");
            b.append("<div class=line><div><a href='/item?id=").append(v(x,"id")).append("'><strong>").append(v(x,"title"))
             .append("</strong></a><p>").append(v(x,"pack")).append(" · ").append(money(x,"price")).append("</p></div>")
             .append(form(r,"/bag")).append(hidden("id",x.get("id")))
             .append("<label>Số lượng<input type=number min=0 max=99 name=qty value='").append(v(x,"qty")).append("' required></label>")
             .append(button("Cập nhật")).append("</form></div>");
        }
        return b+"</div><div class=checkout-summary><strong>Tạm tính "+money(total)+"</strong><a class=button href='/checkout'>Tiến hành đặt hàng →</a></div>";
    }
    static String auth(App.Request r,String path){
        boolean register=path.equals("/register");
        String next=r.get("next").equals("checkout")?"?next=checkout":"";
        String body=heading(register?"Tạo tài khoản":"Đăng nhập","")+
            (r.get("created").equals("1")?"<p class=notice>Đã tạo tài khoản. Bạn có thể đăng nhập.</p>":"")+
            form(r,path)+hidden("next",r.get("next"))+
            (register?input("Họ và tên","name","","text",80,true):"")+
            input("Email","email","","email",254,true)+input(register?"Mật khẩu (ít nhất 10 ký tự)":"Mật khẩu","password","","password",100,true)+
            button(register?"Đăng ký":"Đăng nhập")+"</form>";
        body+="<p class=after-form>"+(register?"Đã có tài khoản? <a href='/login"+next+"'>Đăng nhập</a>":"Chưa có tài khoản? <a href='/register"+next+"'>Đăng ký</a>")+"</p>";
        return "<div class='panel narrow'>"+body+"</div>";
    }
    static String checkout(App.Request r,List<Map<String,Object>> shops,App.Quote quote,String error){
        StringBuilder b=new StringBuilder(heading("Đặt hàng","Kiểm tra hàng và phí tại cửa hàng bạn chọn."));
        b.append("<section class=panel><form action='/checkout' class=compact><label>Cửa hàng<select name=shop>");
        for(var shop:shops)b.append(opt(shop.get("id"),Db.str(shop,"name"),r.get("shop")));
        b.append("</select></label><label>Hình thức nhận<select name=mode>").append(opt("PICKUP","Nhận tại cửa hàng",r.get("mode")))
         .append(opt("DELIVERY","Giao hàng",r.get("mode"))).append("</select></label><label>Khu vực (nếu giao hàng)<select name=zone>");
        for(var shop:shops)b.append(opt(shop.get("zone"),Db.str(shop,"zone"),r.get("zone")));
        b.append("</select></label>").append(button("Kiểm tra tồn và phí")).append("</form></section>");
        if(!error.isEmpty())return b+"<p class='notice danger'>"+e(error)+"</p>";
        if(quote==null)return b.toString();
        b.append("<div class=two><section class=panel><h2>Người nhận</h2>").append(form(r,"/checkout"))
         .append(hidden("shop",r.get("shop"))).append(hidden("mode",r.get("mode"))).append(hidden("zone",r.get("zone")))
         .append(hidden("token",UUID.randomUUID())).append(hidden("total",quote.total()))
         .append(input("Họ tên","recipient",Db.str(r.customer(),"name"),"text",80,true)).append(input("Điện thoại","phone","","tel",11,true));
        if(r.get("mode").equals("DELIVERY"))b.append(input("Địa chỉ giao","address","","text",240,true));
        else b.append("<p>Nhận tại ").append(v(quote.shop(),"address")).append("</p>");
        b.append("<p>Thanh toán khi nhận hàng (COD).</p>").append(button("Xác nhận đặt hàng")).append("</form></section><aside class=panel><h2>Đơn hàng</h2>");
        for(var x:quote.rows())b.append("<p>").append(v(x,"title")).append(" · ").append(v(x,"pack")).append(" × ").append(v(x,"qty")).append("</p>");
        return b+"<p>Tạm tính "+money(quote.subtotal())+"</p><p>Phí giao "+money(quote.fee())+"</p><strong>Tổng "+money(quote.total())+"</strong></aside></div>";
    }
    static String orders(List<Map<String,Object>> orders){
        StringBuilder b=new StringBuilder(heading("Đơn của tôi",""));
        if(orders.isEmpty())return b+blank("Bạn chưa có đơn hàng.");
        for(var o:orders)b.append("<a class='panel order-link' href='/order?id=").append(v(o,"id")).append("'><b>Đơn #").append(v(o,"id"))
         .append("</b><span>").append(e(status(o.get("status")))).append("</span><strong>").append(money(o,"total")).append("</strong></a>");
        return b.toString();
    }
    static String order(Map<String,Object> order,List<Map<String,Object>> lines){
        StringBuilder b=new StringBuilder(heading("Đơn #"+Db.str(order,"id"),status(order.get("status"))));
        b.append("<section class=panel><h2>Sản phẩm đã đặt</h2>");
        for(var l:lines)b.append("<div class=line><span>").append(v(l,"title")).append(" × ").append(v(l,"qty"))
         .append("</span><b>").append(money(l,"unitPrice")).append(" / đơn vị</b></div>");
        b.append("<p>Phí giao ").append(money(order,"fee")).append("</p><strong>Tổng ").append(money(order,"total"))
         .append("</strong><p>").append(v(order,"shopName")).append(" · ").append(v(order,"address"))
         .append("</p><p>Thanh toán khi nhận hàng (COD).</p></section><a href='/orders'>Xem đơn của tôi →</a>");
        return b.toString();
    }
}
