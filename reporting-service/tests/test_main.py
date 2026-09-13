def test_get_balance_nets_to_zero(client, seeded_account):
    response = client.get(f"/accounts/{seeded_account.id}/balance")
    assert response.status_code == 200
    assert response.json() == 0

def test_get_balance_is_none(client, seeded_account_no_balance):
    response = client.get(f"/accounts/{seeded_account_no_balance.id}/balance")
    assert response.status_code == 200
    assert response.json() == 0

def test_get_statements(client, seeded_statement_account):
    response = client.get(f"/statements/{seeded_statement_account.id}")

    assert response.status_code == 200
    data = response.json()
    assert len(data) == 4

    for i, d in enumerate(data):
        assert d['currency'] == 'USD'
        if i == 0:
            assert d['running_balance'] == 40
            assert d['direction'] == 'CREDIT'
            assert d['amount'] == 40
        elif i == 1:
            assert d['running_balance'] == -10
            assert d['direction'] == 'DEBIT'
            assert d['amount'] == 50
        elif i == 2:
            assert d['running_balance'] == -40
            assert d['direction'] == 'DEBIT'
            assert d['amount'] == 30
        elif i == 3:
            assert d['running_balance'] == 60
            assert d['direction'] == 'CREDIT'
            assert d['amount'] == 100


def test_get_account_summary(client, seeded_summary_account):
    response = client.get(f"/accounts/{seeded_summary_account.id}/summary")

    assert response.status_code == 200
    data = response.json()
    assert len(data) == 2

    for d in data:
        if d['type'] == 'DEPOSIT':
            assert d['count'] == 2
            assert d['direction'] == 'CREDIT'
            assert d['total_amount'] == 150
        elif d['type'] == 'WITHDRAWAL':
            assert d['count'] == 1
            assert d['direction'] == 'DEBIT'
            assert d['total_amount'] == -40
        else:
            assert False, f"unexpected group: {d}"