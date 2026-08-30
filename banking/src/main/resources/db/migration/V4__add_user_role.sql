ALTER TABLE users 
ADD COLUMN role VARCHAR(10) NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN'));

UPDATE users SET role = 'ADMIN' WHERE username = 'system';