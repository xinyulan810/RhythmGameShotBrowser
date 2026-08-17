import sqlite3
import sys

# Usage: python check_db.py [path/to/shots.db]
db = sys.argv[1] if len(sys.argv) > 1 else 'shots.db'
conn = sqlite3.connect(db)
c = conn.cursor()
print('total:', c.execute('SELECT COUNT(*) FROM shots').fetchone()[0])
print('ocr_done:', c.execute('SELECT COUNT(*) FROM shots WHERE indexed=1').fetchone()[0])
print('ocr_nonempty:', c.execute("SELECT COUNT(*) FROM shots WHERE ocr_text != ''").fetchone()[0])
print('--- group by game ---')
for row in c.execute('SELECT game, COUNT(*) FROM shots GROUP BY game ORDER BY COUNT(*) DESC'):
    print(row)
print('--- sample ocr texts ---')
for row in c.execute("SELECT game, display_name, substr(ocr_text,1,80) FROM shots WHERE ocr_text != '' LIMIT 12"):
    print(row)
print('--- no-pkg screenshots (should be 0) ---')
print(c.execute("SELECT COUNT(*) FROM shots WHERE pkg = display_name OR pkg=''").fetchone()[0])
conn.close()
