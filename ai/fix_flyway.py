import pymysql
conn = pymysql.connect(host='localhost', user='root', password='1234', database='moment_weaver', charset='utf8mb4')
cur = conn.cursor()
# 看下 V14 当前状态
cur.execute('SELECT version, description, type, success FROM flyway_schema_history WHERE version = %s', ('14',))
print('Before delete:', cur.fetchall())
# V14 是给 MongoDB 集合的 SQL 误加，磁盘上已无文件，直接删记录
cur.execute('DELETE FROM flyway_schema_history WHERE version = %s', ('14',))
conn.commit()
print('Deleted rows:', cur.rowcount)
# 确认
cur.execute('SELECT version, description, type, success FROM flyway_schema_history WHERE version = %s', ('14',))
print('After delete:', cur.fetchall())
conn.close()
