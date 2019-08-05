# Calculate the product of data elements of a list
# for MYΥ-505 - Computer Architecture
# Department of Computer Engineering, University of Ioannina
# Aris Efthymiou

       .globl mulproc, listProd # declare the label main as global. 
        
        ###############################################################################
        # Data input.
        ###############################################################################
       .data
# 1st item - head of the list!
n1_d: .word 1
n1_n: .word n2_d  # point to (beginning of) n2

# 3rd item
n3_d: .word 3
n3_n: .word n4_d

# 2nd item
n2_d: .word 2
n2_n: .word n3_d

# 5th item
n5_d: .word 5
n5_n: .word 0 # This is the last iterm in the list

# 4th item
n4_d: .word 4
n4_n: .word n5_d

# Alternative head of list. Value 0 to test mult by 0
na_d: .word 0
na_n: .word n2_d  # point to (beginning of) n2

        .text 
        # These are for providing input and testing, don't change in your
        #  final submission
        la    $a0, n1_d
        jal   listProd
        addu  $s0, $v0, $zero   # Move the result to s0
        # Try it with a null pointer
        addu  $a0, $zero, $zero
        jal   listProd
        addu  $s1, $v0, $zero   # Move the result to s1
        # Try it with 1 item list
        la    $a0, n5_d
        jal   listProd
        addu  $s2, $v0, $zero   # Move the result to s2
        # ----- Try mult by 0
        la    $a0, na_d
        jal   listProd
        addu  $s3, $v0, $zero   # Move the result to s3


        addiu      $v0, $zero, 10    # system service 10 is exit
        syscall                      # we are outta here.
        ########################################################################
        #  Write your code here. Leave main as is.
        ########################################################################

# Only works for unsigned numbers
mulproc:
        addu  $v0, $zero, $zero   # result = 0
        sltu  $t0, $a0, $a1
        bne   $t0, $zero, loop_mult
        # swap by xor
        xor   $a0, $a0, $a1
        xor   $a1, $a1, $a0
        xor   $a0, $a0, $a1
        # now a0 < a1
loop_mult:
        beq   $a0, $zero, leave_mult
        addu  $v0, $v0, $a1
        addiu $a0, $a0, -1
        j     loop_mult
leave_mult:
        jr    $ra # return


listProd:
        addiu $sp, $sp, -8
        sw    $ra, 0x4($sp) 
        sw    $a0, 0x0($sp) 
        addiu $v0, $zero, 1
        beq   $a0, $zero, leave_listProd
        lw    $a0, 4($a0) # get next address
        jal   listProd
        addu  $a1, $v0, $zero  # move result into a1
        lw    $a0, 0x0($sp)  # get my original parameter
        lw    $a0, 0($a0)    # get data
        jal   mulproc
leave_listProd:
        lw    $ra, 0x4($sp)
        addiu $sp, $sp, 8
        jr    $ra


