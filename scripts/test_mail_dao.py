"""Run the actual @Query SQL from Store.kt on local SQLite, without Android/Gradle.
This verifies SQL state transitions, not Room's annotation processor or Android runtime.
"""
import re
import sqlite3
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / 'app/src/main/java/kr/local/settlement/Store.kt').read_text()
QUERIES = dict((name, sql) for sql, name in re.findall(
    r'@Query\("([^"\n]+)"\)\s*(?:suspend\s+)?fun\s+(\w+)', SOURCE))
ENTITY = SOURCE.split('data class MailRow(', 1)[1].split('\n)', 1)[0]
FIELDS = re.findall(r'val\s+(\w+):\s*(String|Int|Long)', ENTITY)


def database(path=':memory:'):
    db = sqlite3.connect(path)
    db.row_factory = sqlite3.Row
    columns = [f'"{name}" {"TEXT" if kind == "String" else "INTEGER"} NOT NULL' +
               (' PRIMARY KEY' if name == 'id' else '') for name, kind in FIELDS]
    db.execute('CREATE TABLE mails (' + ','.join(columns) + ',UNIQUE(batch,position))')
    return db


def add(db, id='one', state='PENDING', batch='a', position=1):
    values = {name: '' if kind == 'String' else 0 for name, kind in FIELDS}
    values.update(id=id, state=state, batch=batch, position=position)
    db.execute('INSERT INTO mails (' + ','.join(values) + ') VALUES (' +
               ','.join(':' + name for name in values) + ')', values)
    db.commit()


class MailDaoSQLTest(unittest.TestCase):
    def setUp(self):
        self.db = database()
        self.addCleanup(self.db.close)

    def query(self, method, **parameters):
        return self.db.execute(QUERIES[method], parameters)

    def state(self, id='one'):
        return self.query('get', id=id).fetchone()['state']

    def test_every_dao_query_is_extracted(self):
        self.assertEqual({'observe', 'all', 'batch', 'get', 'result', 'claim', 'retry',
                          'recoverConnections', 'recoverSending', 'cancelPending'}, set(QUERIES))

    def test_pending_mail_can_only_be_claimed_once(self):
        add(self.db)
        self.assertEqual(1, self.query('claim', id='one').rowcount)
        self.assertEqual(0, self.query('claim', id='one').rowcount)
        self.assertEqual('CONNECTING', self.state())

    def test_accepted_and_confirmed_mails_cannot_be_retried_or_claimed(self):
        for i, state in enumerate(('ACCEPTED', 'CONFIRMED'), 1):
            add(self.db, str(i), state, position=i)
            self.assertEqual(0, self.query('retry', id=str(i)).rowcount)
            self.assertEqual(0, self.query('claim', id=str(i)).rowcount)

    def test_uncertain_mail_never_becomes_pending_by_retry(self):
        add(self.db, state='UNCERTAIN')
        self.assertEqual(0, self.query('retry', id='one').rowcount)
        self.assertEqual(0, self.query('claim', id='one').rowcount)
        self.assertEqual('UNCERTAIN', self.state())

    def test_only_failed_and_cancelled_are_retryable(self):
        for i, state in enumerate(('FAILED', 'CANCELLED'), 1):
            add(self.db, str(i), state, position=i)
            self.assertEqual(1, self.query('retry', id=str(i)).rowcount)
            self.assertEqual('PENDING', self.state(str(i)))

    def test_recovery_preserves_accepted_and_quarantines_inflight(self):
        for i, state in enumerate(('CONNECTING', 'SENDING', 'ACCEPTED', 'PENDING'), 1):
            add(self.db, str(i), state, position=i)
        self.query('recoverConnections')
        self.query('recoverSending')
        self.assertEqual(['FAILED', 'UNCERTAIN', 'ACCEPTED', 'PENDING'],
                         [self.state(str(i)) for i in range(1, 5)])

    def test_cancel_is_scoped_to_batch_and_unsent_rows(self):
        add(self.db, 'one', 'PENDING', 'a', 1)
        add(self.db, 'two', 'ACCEPTED', 'a', 2)
        add(self.db, 'three', 'PENDING', 'b', 1)
        self.query('cancelPending', batch='a')
        self.assertEqual('CANCELLED', self.state('one'))
        self.assertEqual('ACCEPTED', self.state('two'))
        self.assertEqual('PENDING', self.state('three'))

    def test_duplicate_batch_position_is_rejected(self):
        add(self.db)
        with self.assertRaises(sqlite3.IntegrityError):
            add(self.db, 'other', position=1)

    def test_two_database_connections_cannot_claim_same_mail(self):
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / 'state.db')
            with database(path) as first:
                add(first)
                with sqlite3.connect(path) as second:
                    self.assertEqual(1, first.execute(QUERIES['claim'], {'id': 'one'}).rowcount)
                    first.commit()
                    self.assertEqual(0, second.execute(QUERIES['claim'], {'id': 'one'}).rowcount)

    def test_smtp_acceptance_response_is_preserved(self):
        add(self.db)
        self.query('result', id='one', state='ACCEPTED', time=123,
                   response='250 2.0.0 queued dummy-id', error='')
        row = self.query('get', id='one').fetchone()
        self.assertEqual('250 2.0.0 queued dummy-id', row['response'])
        self.assertEqual(123, row['time'])
        self.assertEqual('ACCEPTED', row['state'])


if __name__ == '__main__':
    unittest.main()
