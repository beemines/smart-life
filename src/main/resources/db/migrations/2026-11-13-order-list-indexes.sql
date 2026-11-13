-- Actual 10,000-order mine plans scanned all user rows and sorted for LIMIT 21.
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' AND index_name='idx_order_user_time')=0,'ALTER TABLE tb_voucher_order ADD INDEX idx_order_user_time(user_id,create_time DESC,id DESC)','SELECT 1');
PREPARE order_list_ddl FROM @ddl;
EXECUTE order_list_ddl;
DEALLOCATE PREPARE order_list_ddl;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' AND index_name='idx_order_user_status_time')=0,'ALTER TABLE tb_voucher_order ADD INDEX idx_order_user_status_time(user_id,status,create_time DESC,id DESC)','SELECT 1');
PREPARE order_list_ddl FROM @ddl;
EXECUTE order_list_ddl;
DEALLOCATE PREPARE order_list_ddl;
