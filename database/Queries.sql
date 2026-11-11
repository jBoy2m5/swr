USE BepMeMoiMiniDB;
GO
SELECT p.id,p.title,p.category,p.pack,p.price,s.name,i.onHand,i.reserved,i.available
FROM dbo.Products p JOIN dbo.Stock i ON i.product=p.id JOIN dbo.Shops s ON s.id=i.shop;
SELECT o.id,o.created,o.total,i.title,i.qty,i.unitPrice
FROM dbo.Orders o JOIN dbo.OrderItems i ON i.orderId=o.id ORDER BY o.id DESC,i.product;
GO
