CREATE TABLE enrollment_capacity (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  total INTEGER NOT NULL CHECK (total BETWEEN 0 AND 10000)
) STRICT;
-- statement-breakpoint
INSERT INTO enrollment_capacity (id, total) VALUES (1, 0);
-- statement-breakpoint
CREATE TABLE enrollments (
  subject TEXT PRIMARY KEY NOT NULL CHECK (length(subject) BETWEEN 1 AND 255),
  email TEXT NOT NULL CHECK (length(email) BETWEEN 3 AND 254),
  status TEXT NOT NULL DEFAULT 'enrolled' CHECK (status = 'enrolled'),
  consent_version TEXT NOT NULL CHECK (consent_version = 'rambler-beta-v1'),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
) STRICT;
-- statement-breakpoint
CREATE TRIGGER enrollment_capacity_guard BEFORE INSERT ON enrollments
WHEN NOT EXISTS (SELECT 1 FROM enrollments WHERE subject = NEW.subject)
  AND (SELECT total FROM enrollment_capacity WHERE id = 1) >= 10000
BEGIN
  SELECT RAISE(ABORT, 'enrollment_capacity_reached');
END;
-- statement-breakpoint
CREATE TRIGGER enrollment_capacity_insert AFTER INSERT ON enrollments
BEGIN
  UPDATE enrollment_capacity SET total = total + 1 WHERE id = 1;
END;
-- statement-breakpoint
CREATE TRIGGER enrollment_capacity_delete AFTER DELETE ON enrollments
BEGIN
  UPDATE enrollment_capacity SET total = total - 1 WHERE id = 1;
END;
