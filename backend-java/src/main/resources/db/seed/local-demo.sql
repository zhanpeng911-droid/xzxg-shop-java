-- Demo rows copied from 001_mysql_schema.sql. Applied by DemoSeedRunner only when
-- shop.seed.demo-enabled=true (local profile). INSERT IGNORE keeps it idempotent.

INSERT IGNORE INTO merchants (merchant_id, name, logo_url, description, service_phone, status) VALUES
('m_001', '小猪数码旗舰店', '/placeholder-merchant.svg', '主营手机、耳机、智能设备和办公外设。', '400-000-0000', 'active');

INSERT IGNORE INTO promotion_rules (
  promotion_id, name, scope, merchant_id, type, threshold_amount, discount_amount, discount_rate, stackable, start_at, end_at, status
) VALUES
('promo_platform_001', '平台满 300 减 30', 'platform', '', 'full_reduction', 300.00, 30.00, 0.0000, TRUE, '2026-01-01 00:00:00', '2026-12-31 23:59:59', 'active'),
('promo_m_001_001', '小猪数码满 1000 减 80', 'merchant', 'm_001', 'full_reduction', 1000.00, 80.00, 0.0000, TRUE, '2026-01-01 00:00:00', '2026-12-31 23:59:59', 'active');

INSERT IGNORE INTO coupons (
  coupon_id, name, scope, merchant_id, type, threshold_amount, discount_amount, total_count, claimed_count, per_user_limit, start_at, end_at, status
) VALUES
('coupon_platform_001', '平台新人满 200 减 20', 'platform', '', 'fixed_amount', 200.00, 20.00, 10000, 0, 1, '2026-01-01 00:00:00', '2026-12-31 23:59:59', 'active'),
('coupon_m_001_001', '小猪数码满 500 减 50', 'merchant', 'm_001', 'fixed_amount', 500.00, 50.00, 10000, 0, 1, '2026-01-01 00:00:00', '2026-12-31 23:59:59', 'active');

INSERT IGNORE INTO categories (category_id, parent_id, name, sort_order) VALUES
('c_phone', '', '手机', 10),
('c_mouse', '', '鼠标', 20);

INSERT IGNORE INTO products (
  product_id, merchant_id, name, brand, category_id, image_url, image_urls_json,
  price, market_price, stock_quantity, stock_status, tags_json, selling_points_json,
  recommend_reason, risk_notes_json, attributes_json, suitable_for_json,
  not_suitable_for_json, description, status, sort_order
) VALUES
(
  'p_001', 'm_001', 'X Phone 12', 'X', 'c_phone',
  'https://images.unsplash.com/photo-1511707171634-5f897ff02aa9?auto=format&fit=crop&w=640&q=80',
  JSON_ARRAY('https://images.unsplash.com/photo-1511707171634-5f897ff02aa9?auto=format&fit=crop&w=640&q=80'),
  2999.00, 3299.00, 84, 'in_stock',
  JSON_ARRAY('拍照', '预算内', '抓拍'),
  JSON_ARRAY('高速对焦', '儿童抓拍模式', '256GB 存储'),
  '抓拍和对焦能力适合日常拍照，价格为 2999 元。',
  JSON_ARRAY(),
  JSON_ARRAY(JSON_OBJECT('key', '存储', 'value', '256GB'), JSON_OBJECT('key', '重量', 'value', '189', 'unit', 'g'), JSON_OBJECT('key', '屏幕', 'value', '6.5 英寸 OLED')),
  JSON_ARRAY('拍娃', '日常拍照', '预算敏感'),
  JSON_ARRAY(),
  '适合预算内拍照和日常使用的手机。',
  'active', 10
),
(
  'p_002', 'm_001', 'Y Camera Max', 'Y', 'c_phone',
  'https://images.unsplash.com/photo-1598327105666-5b89351aff97?auto=format&fit=crop&w=640&q=80',
  JSON_ARRAY('https://images.unsplash.com/photo-1598327105666-5b89351aff97?auto=format&fit=crop&w=640&q=80'),
  3499.00, 3899.00, 32, 'in_stock',
  JSON_ARRAY('影像旗舰', '长焦', '续航'),
  JSON_ARRAY('长焦表现好', '夜景稳定', '续航更强'),
  '影像和续航配置更高，价格为 3499 元。',
  JSON_ARRAY(),
  JSON_ARRAY(JSON_OBJECT('key', '存储', 'value', '256GB'), JSON_OBJECT('key', '重量', 'value', '204', 'unit', 'g')),
  JSON_ARRAY('旅行拍照', '重视续航'),
  JSON_ARRAY(),
  '影像能力更强，但价格超过 3000。',
  'active', 20
),
(
  'p_mouse_001', 'm_001', 'Quiet Mouse S', 'Q', 'c_mouse',
  'https://images.unsplash.com/photo-1527814050087-3793815479db?auto=format&fit=crop&w=640&q=80',
  JSON_ARRAY('https://images.unsplash.com/photo-1527814050087-3793815479db?auto=format&fit=crop&w=640&q=80'),
  129.00, 159.00, 120, 'in_stock',
  JSON_ARRAY('静音', '办公', '无线'),
  JSON_ARRAY('静音微动', '人体工学', '长续航'),
  '静音、无线、握持舒适，更适合办公和宿舍。',
  JSON_ARRAY('不适合高强度电竞'),
  JSON_ARRAY(JSON_OBJECT('key', '连接', 'value', '2.4G 无线 + 蓝牙'), JSON_OBJECT('key', '重量', 'value', '88', 'unit', 'g')),
  JSON_ARRAY('办公', '宿舍', '图书馆'),
  JSON_ARRAY('高强度电竞'),
  '适合安静办公环境的无线鼠标。',
  'active', 30
);

INSERT IGNORE INTO product_skus (sku_id, product_id, sku_name, price, stock_quantity, stock_status, specs_json, is_default) VALUES
('sku_001', 'p_001', 'X Phone 12 标准版', 2999.00, 84, 'in_stock', JSON_OBJECT('版本', '标准版'), TRUE),
('sku_002', 'p_002', 'Y Camera Max 标准版', 3499.00, 32, 'in_stock', JSON_OBJECT('版本', '标准版'), TRUE),
('sku_mouse_001', 'p_mouse_001', 'Quiet Mouse S 标准版', 129.00, 120, 'in_stock', JSON_OBJECT('版本', '标准版'), TRUE);

INSERT IGNORE INTO knowledge_chunks (chunk_id, title, snippet, source, sort_order) VALUES
('ck_phone_001', 'X Phone 12 商品详情', 'X Phone 12 支持高速对焦、儿童抓拍模式，官方零售价 2999 元。', 'mysql_seed', 10),
('ck_mouse_001', 'Quiet Mouse S 商品详情', 'Quiet Mouse S 主打静音按键、无线连接和人体工学握持。', 'mysql_seed', 20);

INSERT IGNORE INTO knowledge_documents (document_id, merchant_id, title, doc_type, content, status, chunk_count) VALUES
('doc_001', 'm_001', '手机商品详情', 'product_detail', 'X Phone 12 支持高速对焦、儿童抓拍模式，官方零售价 2999 元。', 'indexed', 1),
('doc_002', 'm_001', 'Quiet Mouse S 商品详情', 'product_detail', 'Quiet Mouse S 主打静音按键、无线连接和人体工学握持。', 'indexed', 1);

INSERT IGNORE INTO accounts (account_id, username, password_hash, display_name, role, merchant_id, status) VALUES
('acct_user_001', 'user', '90aae915da86d3b3a4da7a996bc264bfbaf50a953cbbe8cd3478a2a6ccc7b900', '演示用户', 'user', '', 'active'),
('acct_merchant_001', 'merchant', '0b2a8a42a665ad403419c5f3f0d6cea853357272459d8e4c30a0900dd4718ebc', '小猪数码运营', 'merchant', 'm_001', 'active'),
('acct_admin_001', 'admin', 'ac0e7d037817094e9e0b4441f9bae3209d67b02fa484917065f71b16109a1a78', '平台管理员', 'admin', '', 'active');
