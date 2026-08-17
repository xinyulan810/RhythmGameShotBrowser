import sqlite3
import sys

# Usage: python check_db2.py [path/to/shots.db]
db = sys.argv[1] if len(sys.argv) > 1 else 'shots.db'
conn = sqlite3.connect(db)
c = conn.cursor()
print('total:', c.execute('SELECT COUNT(*) FROM shots').fetchone()[0])
print('ocr_done:', c.execute('SELECT COUNT(*) FROM shots WHERE indexed=1').fetchone()[0])
print('has_PERFECT:', c.execute("SELECT COUNT(*) FROM shots WHERE ocr_text LIKE '%PERFECT%'").fetchone()[0])
print('has_combo:', c.execute("SELECT COUNT(*) FROM shots WHERE ocr_text LIKE '%combo%'").fetchone()[0])
print('--- search PERFECT sample ---')
for row in c.execute("SELECT game, display_name FROM shots WHERE ocr_text LIKE '%PERFECT%' LIMIT 5"):
    print(row)
conn.close()
