# Factorial in MIPS assembly
# by George Z. Zachos

	.globl main
	
	.text
	
main:
	addi	$a0, $zero, 13
	jal	fact
	add     $a0, $v0, $zero
	li      $v0, 1
	syscall
	beq	$v0, 1, test
exit:
	li	$v0, 10 
        syscall
	
fact:
	slti	$t0, $a0, 1
	beq	$t0, $zero, else
	addi	$v0, $zero, 1
	jr	$ra
else:
	addi	$sp, $sp, -8
	sw	$a0, 4($sp)
	sw	$ra, 0($sp)
	addi	$a0, $a0, -1
	jal	fact
	lw	$ra, 0($sp)
	lw	$a0, 4($sp)
	addi	$sp, $sp, 8
	mul	$v0, $a0, $v0
	jr	$ra

test:
	nop
	j exit
