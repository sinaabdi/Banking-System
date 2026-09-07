package main

import (
	"fmt"
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestTriangulate_sanityCheck(t *testing.T) {
	fromRate := 1.0
	toRate := 0.92

	want := 0.92

	got := triangulate(fromRate, toRate)

	assert.Equal(t, want, got, fmt.Sprintf("The calculated rate should be %f, but got %f", want, got))
}

func TestTriangulate_nonUSD(t *testing.T) {
	fromRate := 0.92
	toRate := 0.79

	want := 0.79 / 0.92

	got := triangulate(fromRate, toRate)

	assert.Equal(t, want, got, fmt.Sprintf("The calculated rate should be %f, but got %f", want, got))
}

func TestTriangulate_selfConversion(t *testing.T) {
	fromRate := 0.92
	toRate := 0.92

	want := 1.0

	got := triangulate(fromRate, toRate)

	assert.Equal(t, want, got, fmt.Sprintf("The calculated rate should be %f, but got %f", want, got))
}

func TestApplyDrift_stayWithExpectedRange(t *testing.T) {
	drift := 0.005

	rate := 0.5
	for i := 0; i < 5000; i++ {
		upperBound := rate * (1 + drift)
		lowerBound := rate * (1 - drift)
		got := applyDrift(rate)

		if got > upperBound || got < lowerBound {
			t.Fatalf("apply drift is not in the expected boundry. want between %f and %f, but got %f", lowerBound, upperBound, got)
		}
	}
}

