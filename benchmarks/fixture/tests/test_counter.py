from src.counter import Counter


def test_bump_increments_once() -> None:
    assert Counter().bump() == 1
