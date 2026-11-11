IF DB_ID(N'BepMeMoiMiniDB') IS NULL EXEC(N'CREATE DATABASE BepMeMoiMiniDB');
GO
USE BepMeMoiMiniDB;
GO
SET XACT_ABORT ON;
IF OBJECT_ID(N'dbo.Customers',N'U') IS NOT NULL RETURN;
BEGIN TRANSACTION;
CREATE TABLE dbo.Customers(
 id int IDENTITY PRIMARY KEY, name nvarchar(80) NOT NULL,
 email nvarchar(254) NOT NULL UNIQUE, passwordHash varchar(100) NOT NULL
);
CREATE TABLE dbo.Products(
 id int IDENTITY PRIMARY KEY, title nvarchar(100) NOT NULL, category varchar(15) NOT NULL,
 pack nvarchar(40) NOT NULL, description nvarchar(550) NOT NULL,
 price int NOT NULL CHECK(price>=0)
);
CREATE TABLE dbo.Shops(
 id int IDENTITY PRIMARY KEY, name nvarchar(80) NOT NULL, address nvarchar(200) NOT NULL,
 zone nvarchar(80) NOT NULL, fee int NOT NULL CHECK(fee>=0)
);
CREATE TABLE dbo.Stock(
 shop int REFERENCES dbo.Shops(id), product int REFERENCES dbo.Products(id),
 onHand int NOT NULL, reserved int NOT NULL DEFAULT 0, available AS (onHand-reserved),
 CHECK(onHand>=reserved AND reserved>=0), PRIMARY KEY(shop,product)
);
CREATE TABLE dbo.Carts(
 customer int REFERENCES dbo.Customers(id), product int REFERENCES dbo.Products(id),
 qty int NOT NULL CHECK(qty BETWEEN 1 AND 99), PRIMARY KEY(customer,product)
);
CREATE TABLE dbo.Orders(
 id int IDENTITY PRIMARY KEY, customer int NOT NULL REFERENCES dbo.Customers(id),
 checkoutToken char(36) NOT NULL, UNIQUE(customer,checkoutToken),
 shop int NOT NULL REFERENCES dbo.Shops(id), mode varchar(8) NOT NULL CHECK(mode IN ('PICKUP','DELIVERY')),
 recipient nvarchar(80) NOT NULL, phone varchar(11) NOT NULL, address nvarchar(240) NOT NULL,
 fee int NOT NULL CHECK(fee>=0), status varchar(12) NOT NULL DEFAULT 'NEW',
 total bigint NOT NULL CHECK(total>=0), created datetime2 NOT NULL DEFAULT SYSUTCDATETIME()
);
CREATE INDEX IX_Orders_Customer ON dbo.Orders(customer,id DESC);
CREATE TABLE dbo.OrderItems(
 orderId int REFERENCES dbo.Orders(id), product int REFERENCES dbo.Products(id),
 title nvarchar(150) NOT NULL, qty int NOT NULL CHECK(qty>0),
 unitPrice int NOT NULL CHECK(unitPrice>=0), PRIMARY KEY(orderId,product)
);
INSERT dbo.Products(title,category,pack,description,price) VALUES
 (N'Bột mì làm bánh mì','ingredients',N'500 g',N'Bột mì dùng làm bánh mì, đế pizza và bánh mì cuộn. Có chứa lúa mì.',29000),
 (N'Men khô tức thì','ingredients',N'100 g',N'Men khô để trộn trực tiếp với bột. Đóng kín gói sau khi mở.',58000),
 (N'Sô cô la đen dạng nút','ingredients',N'250 g',N'Dùng để đun chảy hoặc trộn vào bột bánh. Kiểm tra thông tin dị ứng trên bao bì.',92000),
 (N'Khuôn bánh tròn','tools',N'18 cm',N'Khuôn tròn cho bánh bông lan và bánh phô mai.',115000),
 (N'Phới lồng inox','tools',N'25 cm',N'Dùng để đánh trứng và trộn các hỗn hợp lỏng.',68000),
 (N'Cân bếp điện tử','equipment',N'5 kg',N'Cân nguyên liệu có chức năng trừ bì, tải tối đa 5 kg.',249000);
INSERT dbo.Shops(name,address,zone,fee) VALUES
 (N'Bếp Đà Nẵng',N'18 Đường Mẫu, Đà Nẵng',N'Đà Nẵng',24000),
 (N'Bếp Sài Gòn',N'36 Đường Mẫu, TP. Hồ Chí Minh',N'TP. Hồ Chí Minh',29000);
INSERT dbo.Stock(shop,product,onHand)
SELECT s.id,p.id,CASE WHEN p.category='equipment' THEN 9 ELSE 25 END
FROM dbo.Shops s CROSS JOIN dbo.Products p;
COMMIT;
GO
