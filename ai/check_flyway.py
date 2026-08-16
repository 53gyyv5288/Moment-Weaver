import pymysql
conn = pymysql.connect(host='localhost', user='root', password='1234', database='moment_weaver', charset='utf8mb4')
cur = conn.cursor()
cur.execute('SELECT version, description, type, success FROM flyway_schema_history ORDER BY installed_rank')
for r in cur.fetchall():
    print(r)
conn.close()
