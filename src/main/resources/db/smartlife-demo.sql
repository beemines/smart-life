-- Optional synthetic demo fixtures. No users, phone numbers or source data.
SET NAMES utf8mb4;
USE smartlife;

INSERT INTO tb_shop_type (id, name, icon, sort)
VALUES (1, 'Cafe', '', 1), (2, 'Bookstore', '', 2)
ON DUPLICATE KEY UPDATE id = VALUES(id);

INSERT INTO tb_shop
    (id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours)
VALUES
    (1, 'SmartLife Cafe', 1, '', 'Demo District', '1 Example Street', 113.0, 23.0, 30, 0, 0, 45, '09:00-21:00'),
    (2, 'SmartLife Books', 2, '', 'Demo District', '2 Example Street', 113.1, 23.1, 50, 0, 0, 48, '10:00-22:00')
ON DUPLICATE KEY UPDATE id = VALUES(id);
