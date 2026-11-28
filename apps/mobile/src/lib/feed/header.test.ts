import { describe, expect, it } from 'vitest';

import { classHeader, guardianHeader, guardianSchool, isSwitchable, schoolHeader, type HeaderChild } from './header';

const kid = (studentId: string, givenName: string, schools: HeaderChild['schools']): HeaderChild => ({ studentId, givenName, schools });
const hana = kid('h', 'Hana', [{ schoolName: 'Riverside', classes: [{ name: '5A' }] }]);
const dawit = kid('d', 'Dawit', [{ schoolName: 'Riverside', classes: [{ name: '2B' }] }]);
const selam = kid('s', 'Selam', [{ schoolName: 'Hillcrest', classes: [{ name: '1C' }] }]);

describe('guardianHeader', () => {
  it('titles one selected child and puts their school and class above', () => {
    expect(guardianHeader([hana, dawit], 'h')).toEqual({ eyebrow: 'Riverside · 5A', title: 'Hana' });
  });
  it('treats an only child as selected', () => {
    expect(guardianHeader([hana], null)).toEqual({ eyebrow: 'Riverside · 5A', title: 'Hana' });
  });
  it('has no title for all children and names the school only when it is the same for all', () => {
    expect(guardianHeader([hana, dawit], null)).toEqual({ eyebrow: 'Riverside', title: null });
    expect(guardianHeader([hana, selam], null)).toEqual({ eyebrow: null, title: null });
  });
  it('falls back to all children when the selection is stale', () => {
    expect(guardianHeader([hana, dawit], 'gone')).toEqual({ eyebrow: 'Riverside', title: null });
  });
  it('shows only the school for a child in several classes, and nothing for a child without one', () => {
    const two = kid('t', 'Tigist', [{ schoolName: 'Riverside', classes: [{ name: '3A' }, { name: 'Choir' }] }]);
    expect(guardianHeader([two], 't')).toEqual({ eyebrow: 'Riverside', title: 'Tigist' });
    expect(guardianHeader([kid('n', 'Nati', [])], 'n')).toEqual({ eyebrow: null, title: 'Nati' });
    expect(guardianHeader([], null)).toEqual({ eyebrow: null, title: null });
  });
});

describe('guardianSchool', () => {
  it('names the logo after the school the shown children share', () => {
    expect(guardianSchool([hana, dawit], null)).toBe('Riverside');
    expect(guardianSchool([hana, selam], 's')).toBe('Hillcrest');
  });
  it('has none for children in different schools', () => {
    expect(guardianSchool([hana, selam], null)).toBeNull();
    expect(guardianSchool([], null)).toBeNull();
  });
});

describe('classHeader and schoolHeader', () => {
  it('puts the school above the class', () => {
    expect(classHeader('Riverside', '5A')).toEqual({ eyebrow: 'Riverside', title: '5A' });
  });
  it('leaves out what is not known', () => {
    expect(classHeader(undefined, '5A')).toEqual({ eyebrow: null, title: '5A' });
    expect(classHeader('  ', undefined)).toEqual({ eyebrow: null, title: null });
    expect(schoolHeader('Riverside')).toEqual({ eyebrow: null, title: 'Riverside' });
    expect(schoolHeader(undefined)).toEqual({ eyebrow: null, title: null });
  });
});

describe('isSwitchable', () => {
  it('offers the dropdown only with a choice', () => {
    expect(isSwitchable([])).toBe(false);
    expect(isSwitchable([1])).toBe(false);
    expect(isSwitchable([1, 2])).toBe(true);
  });
});
