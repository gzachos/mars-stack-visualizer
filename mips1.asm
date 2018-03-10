
	.globl main
	.text

main:

#	li	$t0, 2147483647
	li	$t0, 2439
	addi	$sp, $sp, -4
	sw	$t0, 0($sp)
	addi	$sp, $sp, 4
#	lw	$t1, -4($sp)
#	li	$a0, 5

	li	$t1, 255
	addi	$sp, $sp, -8
	sw	$t1, 0($sp)
	addi	$sp, $sp, 8
#	lw	$t2, -8($sp)
	
	li	$t2, 256
	addi	$sp, $sp, -12
	sw	$t2, 0($sp)
	addi	$sp, $sp, 12
#	lw	$t2, -12($sp)

	li	$t3, 168496141
	addi	$sp, $sp, -16
	sh	$t3, 0($sp)
	addi	$sp, $sp, 16

	addi	$sp, $sp, -20
	sh	$t3, 2($sp)
	addi	$sp, $sp, 20

	addi	$sp, $sp, -24
	sw	$t3, 0($sp)
	addi	$sp, $sp, 24


#	addi	$sp, $sp, -8
	
#	la	$a0, matric
#	lw  	$t0, 0($a0)
#	addi	$sp, $sp, -8
#	sw	$t0, 8($sp)
#	li	$t0, 7
#	sw	$t0, 4($sp)
#	li	$t0, 2439
#	sw	$t0, 0($sp)
#	addi	$sp, $sp, 8
	
	li	$v0, 10
	syscall


	.data

matric: .word 2439
