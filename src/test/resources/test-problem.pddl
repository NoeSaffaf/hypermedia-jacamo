(define (problem test)
(:domain hanoi)
(:objects disk1 disk2 disk3)
(:init (smaller disk1 disk2)
       (smaller disk3 disk2)
       (smaller disk1 disk3)
       (on disk1 disk2)
       (clear disk1)
       (clear disk3))
(:goal (clear disk2))
)