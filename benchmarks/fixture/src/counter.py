class Counter:
    def __init__(self) -> None:
        self.value = 0

    def bump(self) -> int:
        self.value += 2  # Deliberate fixture bug: expected increment is one.
        return self.value
