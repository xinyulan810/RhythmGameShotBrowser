import sqlite3
import sys

# Usage: python check_vlm.py [path1 [path2 ...]]  (default: shots.db)
dbs = sys.argv[1:] or ['shots.db']
for f in dbs:
    c = sqlite3.connect(f)
    print(f)
    print('  total:', c.execute('SELECT COUNT(*) FROM shots').fetchone()[0])
    print('  vlm_done:', c.execute('SELECT COUNT(*) FROM shots WHERE vlm_done=1').fetchone()[0])
    print('  song_name非空:', c.execute("SELECT COUNT(*) FROM shots WHERE song_name != ''").fetchone()[0])
    print('  vlm_game非空:', c.execute("SELECT COUNT(*) FROM shots WHERE vlm_game != ''").fetchone()[0])
    c.close()
