# mars-stack-visualizer

## About Mars
[MARS](http://courses.missouristate.edu/KenVollmar/mars/) is a lightweight interactive
development environment (IDE) for programming in MIPS assembly language, intended for
educational-level use with Patterson and Hennessy's Computer Organization and Design.
Developed by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu).

## About this project
The StackVisualizer tool and application allows the user to view in real time the memory modification operations
taking place in the stack segment. The user can also observe how the stack grows.
The address pointed by the stack pointer is displayed in an orange background
while the whole word-length data in a yellow one. Lower addresses have a grey
background (given that stack growth takes place form higher to lower addresses).
The names of the registers whose contents are stored (`sw`, `sh`, `sb` etc.) in the
stack, are shown in the "Stored Reg" column. In the "Call Layout" column, the subroutine
frame (activation record) layout is displayed, with subroutine names placed on the highest
address of the corresponding frames.

## Project Supervisor
 - [Aris Efthymiou](https://www.cse.uoi.gr/~efthym)

## Project Developers
 - [George Z. Zachos](https://www.cse.uoi.gr/~gzachos)
 - [Petros Manousis](https://www.cs.uoi.gr/~pmanousi)

## License
 * Original [MARS LICENSE (MIT)](./MARSlicense.txt).
 * The Stack Visualizer Tool and Application is also licensed under the MIT LICENSE. For more information see [StackVisualizer.java](mars/tools/StackVisualizer.java)

## Source code
The source code was extracted using the following command: ```jar xf Mars4_5.jar```.
