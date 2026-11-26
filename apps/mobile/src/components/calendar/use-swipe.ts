import { useEffect, useRef, useState } from 'react';
import { PanResponder, type GestureResponderHandlers } from 'react-native';

const DISTANCE = 60;

/**
 * Handlers that call `onSwipe(-1)` for a swipe to the right and `onSwipe(1)` for one to the left (next page). Only a clearly
 * horizontal move starts it, so vertical scrolling is never taken over; every swipe has a button as well.
 */
export function useSwipe(onSwipe: (direction: -1 | 1) => void): GestureResponderHandlers {
  const latest = useRef(onSwipe);
  useEffect(() => {
    latest.current = onSwipe;
  });
  // eslint-disable-next-line react-hooks/refs -- the callbacks read the ref only when a gesture ends, never during render
  const [responder] = useState(() =>
    PanResponder.create({
      onMoveShouldSetPanResponder: (_event, gesture) => Math.abs(gesture.dx) > 24 && Math.abs(gesture.dx) > Math.abs(gesture.dy) * 2,
      onPanResponderRelease: (_event, gesture) => {
        if (gesture.dx <= -DISTANCE) latest.current(1);
        else if (gesture.dx >= DISTANCE) latest.current(-1);
      },
    }),
  );
  return responder.panHandlers;
}
